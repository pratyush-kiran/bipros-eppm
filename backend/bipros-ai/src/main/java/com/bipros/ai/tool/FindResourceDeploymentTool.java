package com.bipros.ai.tool;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.resolver.EffectiveRate;
import com.bipros.ai.resolver.EffectiveRateResolver;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Finds where a resource role / designation is deployed across the current
 * project — answers questions like "how many masons are working", "where are
 * electricians assigned", "how many earth-moving units are deployed".
 *
 * The keyword is matched (token-based, normalised, case-insensitive) against
 * BOTH the resource's code/name and the effective role's code/name. That
 * matters because the UI groups assignments by role — a row labelled
 * "Earth Moving" or "Carpenter" is the role, not the underlying resource —
 * so a query like {@code "earth moving"} would otherwise miss every resource
 * playing that role. Whitespace, hyphens, underscores and simple plurals are
 * normalised away ({@code "earth-moving"}, {@code "earth movings"} and
 * {@code "Earth Moving"} all match the role {@code "Earth Moving"}).
 * Aggregates {@link ResourceAssignment} rows for matching resources: total
 * planned/actual units and cost, count of activities, and a top-N list of
 * the activities the role appears on.
 *
 * Implements {@link Tool} directly so {@code @Transactional(readOnly = true)}
 * actually triggers (the parent class's template-method invocation would be a
 * self-call that bypasses Spring's AOP proxy and would blow up on the lazy
 * {@code Resource#resourceType} association under the orchestrator's worker
 * thread).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FindResourceDeploymentTool implements Tool {

    private final ResourceRepository resourceRepository;
    private final ResourceAssignmentRepository assignmentRepository;
    private final ResourceRoleRepository roleRepository;
    private final ActivityRepository activityRepository;
    private final EffectiveRateResolver rateResolver;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "find_resource_deployment";
    }

    @Override
    public String description() {
        return "Find where a resource role / designation / trade is DEPLOYED — i.e. assigned "
                + "to an activity — across the current project. SCOPE: this tool sees ONLY "
                + "resources that have an active resource_assignments row. For catalogue / "
                + "rate questions (size of the priced catalogue, daily rate of an excavator, "
                + "most expensive equipment, top labor categories by rate) use "
                + "query_resource_catalogue instead — it reads the project-agnostic priced "
                + "master at /v1/resources, which can hold hundreds of priced rows even when "
                + "no assignments exist on this project. "
                + "Search by keyword (e.g. \"mason\", \"electrician\", \"helper\", \"crane\", "
                + "\"earth moving\", \"steel fixer\") — matches case-insensitively across the "
                + "resource's code/name AND the role's code/name (so role-level groupings like "
                + "\"Earth Moving\", \"Paving Equipment\", or \"Carpenter\" are matched even when "
                + "individual resources have different names). Whitespace, hyphens, and simple "
                + "plurals are tolerated (\"earth-moving\", \"earth movings\", and \"earth moving\" "
                + "all match the role \"Earth Moving\"). Returns matching resources with their "
                + "effective role(s), total planned vs actual units and cost, count of activities, "
                + "and the top activities ranked by planned units. Use for cross-cutting workforce "
                + "questions like \"how many masons are working\", \"where are the welders\", "
                + "\"how many earth-moving units are deployed\", or \"list every helper booking on "
                + "this project\". Requires a current project in scope. "
                + "Each row carries effective_rate (project pool override → resource base), unit, "
                + "unit_basis (DAY/HOUR/EACH), rate_source, override_applied, and formula_overrides "
                + "so the AI can disclose project-specific rate overrides.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("query", objectMapper.createObjectNode().put("type", "string")
                .put("description", "Keyword that matches a resource role, trade or designation. "
                        + "Token-based, case-insensitive substring match across the resource's "
                        + "code/name AND the role's code/name. Whitespace, hyphens, underscores "
                        + "and simple plurals are normalised (\"earth-moving\", \"earth movings\", "
                        + "\"Earth Moving\" all hit the role \"Earth Moving\"). Examples: \"mason\", "
                        + "\"electrician\", \"helper\", \"crane\", \"earth moving\", \"steel\"."));
        props.set("top_activities", objectMapper.createObjectNode()
                .put("type", "integer").put("minimum", 1).put("maximum", 50).put("default", 10)
                .put("description", "How many of the top activities to list per matching resource."));
        ArrayNode required = objectMapper.createArrayNode();
        required.add("query");
        schema.set("required", required);
        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error(
                    "find_resource_deployment needs a project in scope. Pick a specific project, "
                            + "then re-ask. (Resource assignments are project-scoped.)");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }

        String query = input.path("query").asText("").trim();
        if (query.isEmpty()) {
            return ToolResult.error(
                    "Provide a `query` keyword (e.g. \"mason\", \"electrician\", \"helper\"). "
                            + "I'll match it case-insensitively across resource codes/names and role names.");
        }
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return ToolResult.error(
                    "Provide a `query` keyword with at least one alphanumeric character.");
        }
        int topActivities = Math.max(1, Math.min(50, input.path("top_activities").asInt(10)));

        // Pull all assignments on the project, then narrow to those whose resource
        // OR effective role matches the keyword. Project-scoped scan keeps this
        // bounded; even on very large projects the assignment count is manageable
        // for an in-memory filter.
        List<ResourceAssignment> projectAssignments = assignmentRepository.findByProjectId(projectId);
        Set<UUID> resourceIds = new HashSet<>();
        Set<UUID> assignmentRoleIds = new HashSet<>();
        for (ResourceAssignment a : projectAssignments) {
            if (a.getResourceId() != null) resourceIds.add(a.getResourceId());
            if (a.getRoleId() != null) assignmentRoleIds.add(a.getRoleId());
        }
        if (resourceIds.isEmpty() && assignmentRoleIds.isEmpty()) {
            return ToolResult.ok("No resources are assigned on this project yet.",
                    objectMapper.createObjectNode().set("rows", objectMapper.createArrayNode()));
        }

        Map<UUID, Resource> byId = new HashMap<>();
        Set<UUID> allRoleIds = new HashSet<>(assignmentRoleIds);
        if (!resourceIds.isEmpty()) {
            resourceRepository.findAllById(resourceIds).forEach(r -> {
                byId.put(r.getId(), r);
                // Resource.role is mandatory in the entity, but defend against bad data.
                if (r.getRole() != null && r.getRole().getId() != null) {
                    allRoleIds.add(r.getRole().getId());
                }
            });
        }
        Map<UUID, ResourceRole> roleById = new HashMap<>();
        if (!allRoleIds.isEmpty()) {
            roleRepository.findAllById(allRoleIds).forEach(role -> roleById.put(role.getId(), role));
        }

        // Walk assignments. For each, derive the effective role (assignment.roleId
        // wins, falling back to the resource's own role) and test the keyword
        // tokens against the resource's code/name and the role's code/name.
        Map<UUID, List<ResourceAssignment>> byResource = new HashMap<>();
        Map<UUID, Set<UUID>> rolesPerResource = new HashMap<>();
        Set<UUID> activityIds = new HashSet<>();
        for (ResourceAssignment a : projectAssignments) {
            if (a.getResourceId() == null) continue; // role-only assignment — skip from per-resource rollup
            Resource r = byId.get(a.getResourceId());
            UUID effectiveRoleId = a.getRoleId() != null
                    ? a.getRoleId()
                    : (r != null && r.getRole() != null ? r.getRole().getId() : null);
            ResourceRole role = effectiveRoleId == null ? null : roleById.get(effectiveRoleId);

            if (!matchesAllTokens(tokens, r, role)) continue;

            byResource.computeIfAbsent(a.getResourceId(), k -> new ArrayList<>()).add(a);
            if (effectiveRoleId != null) {
                rolesPerResource.computeIfAbsent(a.getResourceId(), k -> new HashSet<>())
                        .add(effectiveRoleId);
            }
            if (a.getActivityId() != null) activityIds.add(a.getActivityId());
        }

        if (byResource.isEmpty()) {
            return ToolResult.ok(
                    "No resources matching \"" + query + "\" are deployed on this project.",
                    objectMapper.createObjectNode().set("rows", objectMapper.createArrayNode()));
        }

        // Materialise the matching resources in a deterministic order for the row pass.
        List<Resource> matchingResources = new ArrayList<>();
        for (UUID rid : byResource.keySet()) {
            Resource r = byId.get(rid);
            if (r != null) matchingResources.add(r);
        }
        matchingResources.sort(Comparator.comparing(
                (Resource r) -> r.getName() == null ? "" : r.getName(),
                String.CASE_INSENSITIVE_ORDER));

        Map<UUID, Activity> activityById = new HashMap<>();
        if (!activityIds.isEmpty()) {
            activityRepository.findAllById(activityIds).forEach(a -> activityById.put(a.getId(), a));
        }

        ArrayNode rows = objectMapper.createArrayNode();
        int totalAssignments = 0;
        for (Resource r : matchingResources) {
            List<ResourceAssignment> assigns = byResource.getOrDefault(r.getId(), List.of());
            if (assigns.isEmpty()) continue;
            totalAssignments += assigns.size();

            double plannedUnits = sumDouble(assigns, ResourceAssignment::getPlannedUnits);
            double actualUnits = sumDouble(assigns, ResourceAssignment::getActualUnits);
            double remainingUnits = sumDouble(assigns, ResourceAssignment::getRemainingUnits);
            double plannedCost = sumBigDecimal(assigns, ResourceAssignment::getPlannedCost);
            double actualCost = sumBigDecimal(assigns, ResourceAssignment::getActualCost);

            // Top activities for this resource, ranked by planned units desc.
            List<ResourceAssignment> sorted = new ArrayList<>(assigns);
            sorted.sort(Comparator.comparingDouble(
                    (ResourceAssignment a) -> a.getPlannedUnits() == null ? 0.0 : a.getPlannedUnits()
            ).reversed());

            ArrayNode topActs = objectMapper.createArrayNode();
            for (int i = 0; i < Math.min(topActivities, sorted.size()); i++) {
                ResourceAssignment a = sorted.get(i);
                Activity act = a.getActivityId() == null ? null : activityById.get(a.getActivityId());
                ObjectNode actNode = objectMapper.createObjectNode();
                actNode.put("activity_code", act != null ? act.getCode() : null);
                actNode.put("activity_name", act != null ? act.getName() : null);
                actNode.put("planned_units", a.getPlannedUnits());
                actNode.put("actual_units", a.getActualUnits());
                actNode.put("planned_cost", a.getPlannedCost() == null ? null : a.getPlannedCost().doubleValue());
                topActs.add(actNode);
            }

            // Effective role(s) the matching keyword likely hit on. Most resources
            // have a single role; an assignment-level override can introduce a
            // second. Listed so the AI can name the role in its prose.
            List<String> roleNames = new ArrayList<>();
            Set<UUID> rids = rolesPerResource.getOrDefault(r.getId(), Set.of());
            for (UUID rid : rids) {
                ResourceRole role = roleById.get(rid);
                if (role != null && role.getName() != null) roleNames.add(role.getName());
            }
            roleNames.sort(String.CASE_INSENSITIVE_ORDER);

            ObjectNode row = objectMapper.createObjectNode();
            row.put("code", r.getCode());
            row.put("name", r.getName());
            row.put("type", r.getResourceType() != null ? r.getResourceType().getCode() : null);
            row.put("role", roleNames.isEmpty() ? null : String.join(", ", roleNames));

            EffectiveRate er = rateResolver.resolve(projectId, r.getId());
            row.put("unit", er.unit());
            row.put("unit_basis", er.basis());
            row.put("effective_rate", er.rate() == null ? null : er.rate().doubleValue());
            row.put("rate_source", er.source().name());
            row.put("override_applied", er.overrideApplied());
            row.put("base_cost_per_unit", r.getCostPerUnit() == null ? null : r.getCostPerUnit().doubleValue());
            ArrayNode notes = objectMapper.createArrayNode();
            if (er.overrideApplied()) notes.add("rate_overridden_per_project");
            row.set("formula_overrides", notes);

            row.put("activity_count", assigns.size());
            row.put("planned_units_total", plannedUnits);
            row.put("actual_units_total", actualUnits);
            row.put("remaining_units_total", remainingUnits);
            row.put("planned_cost_total", plannedCost);
            row.put("actual_cost_total", actualCost);
            row.set("top_activities", topActs);
            rows.add(row);
        }

        // Order matching resources by activity count desc.
        // (ArrayNode is already populated in iteration order; rebuild sorted.)
        List<ObjectNode> rowList = new ArrayList<>();
        rows.forEach(n -> rowList.add((ObjectNode) n));
        rowList.sort(Comparator.comparingInt(
                (ObjectNode n) -> n.get("activity_count").asInt()).reversed());
        ArrayNode sortedRows = objectMapper.createArrayNode();
        rowList.forEach(sortedRows::add);

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("rows", sortedRows);
        wrapper.put("query", query);
        wrapper.put("matching_resources", rowList.size());
        wrapper.put("total_assignments", totalAssignments);

        String summary = String.format(
                "Found %d resource%s matching \"%s\" — %d total assignment%s across %d activit%s",
                rowList.size(), rowList.size() == 1 ? "" : "s", query,
                totalAssignments, totalAssignments == 1 ? "" : "s",
                activityIds.size(), activityIds.size() == 1 ? "y" : "ies"
        );
        return ToolResult.ok(summary, wrapper);
    }

    private static double sumDouble(
            List<ResourceAssignment> assigns,
            java.util.function.Function<ResourceAssignment, Double> getter) {
        double total = 0.0;
        for (ResourceAssignment a : assigns) {
            Double v = getter.apply(a);
            if (v != null) total += v;
        }
        return total;
    }

    private static double sumBigDecimal(
            List<ResourceAssignment> assigns,
            java.util.function.Function<ResourceAssignment, BigDecimal> getter) {
        double total = 0.0;
        for (ResourceAssignment a : assigns) {
            BigDecimal v = getter.apply(a);
            if (v != null) total += v.doubleValue();
        }
        return total;
    }

    /**
     * Split the user's keyword into normalised tokens. Lowercases, replaces every
     * non-alphanumeric run with a single space, then strips a trailing {@code s}
     * from tokens longer than three characters as a crude plural fold. This lets
     * {@code "earth-moving"}, {@code "earth movings"} and {@code "Earth Moving"}
     * all reduce to {@code [earth, moving]}.
     */
    private static List<String> tokenize(String query) {
        String norm = normalize(query);
        if (norm.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : norm.split(" ")) {
            if (p.isBlank()) continue;
            if (p.length() > 3 && p.endsWith("s")) p = p.substring(0, p.length() - 1);
            out.add(p);
        }
        return out;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    /**
     * True when every query token appears as a substring of the haystack made of
     * the resource's code/name plus the role's code/name (all normalised the same
     * way as the query). All-token requirement keeps multi-word queries like
     * {@code "earth moving"} from spuriously matching a resource named
     * {@code "Earth"} that has no moving connection.
     */
    private static boolean matchesAllTokens(List<String> tokens, Resource r, ResourceRole role) {
        StringBuilder hay = new StringBuilder();
        if (r != null) {
            hay.append(normalize(r.getCode())).append(' ');
            hay.append(normalize(r.getName())).append(' ');
        }
        if (role != null) {
            hay.append(normalize(role.getCode())).append(' ');
            hay.append(normalize(role.getName())).append(' ');
        }
        if (hay.length() == 0) return false;
        String haystack = hay.toString();
        for (String t : tokens) {
            if (!haystack.contains(t)) return false;
        }
        return true;
    }
}
