package com.bipros.ai.tool.graph;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityRelationship;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprIssue;
import com.bipros.project.domain.model.IssueStatus;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprIssueRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Generic 1-hop graph walk over the project domain (Project → Activity → DPR → Issue and
 * Supervisor / WBS branches). Returns the entity's parents, children with counts, and a small
 * sample of recent rows in a single call.
 *
 * <p>Foundational graph primitive: the model uses this when the user asks "everything connected
 * to X", "walk from activity Y", "what's around this DPR", or any cross-entity exploration
 * question that does not match a more specific composite tool.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraverseEntityTool implements Tool {

    private static final int SAMPLE_LIMIT = 5;

    private final ProjectRepository projectRepository;
    private final ActivityRepository activityRepository;
    private final ActivityRelationshipRepository relationshipRepository;
    private final DailyProgressReportRepository dprRepository;
    private final DprIssueRepository issueRepository;
    private final WbsNodeRepository wbsRepository;
    private final ResourceRepository resourceRepository;
    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "traverse_entity";
    }

    @Override
    public String description() {
        return "Generic graph walk: given a start entity (project, activity, wbs_node, dpr, issue, "
                + "or supervisor) return its parents, child entity counts (DPRs, issues, "
                + "resource assignments, subordinates), and a small sample of recent items in one "
                + "call. Use for \"everything connected to activity X\", \"walk from this DPR\", "
                + "\"what's around issue Y\", \"who reports to supervisor Z\", and any cross-entity "
                + "exploration question that does not have a more specific tool. The model receives "
                + "linked_entity_ids so the next tool call can target a specific child without an "
                + "extra discovery round. Project-scoped — needs a project in scope unless "
                + "entity_type=project.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.set("entity_type", mapper.createObjectNode()
                .put("type", "string")
                .put("description",
                        "One of: project, activity, wbs_node, dpr, issue, supervisor."));
        props.set("entity_id", mapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "UUID of the entity. Either entity_id or entity_code must be supplied."));
        props.set("entity_code", mapper.createObjectNode()
                .put("type", "string")
                .put("description",
                        "Code for the entity (project code, activity code, wbs code, resource code). "
                                + "Ignored when entity_type is dpr or issue — those only resolve by id."));
        schema.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("entity_type");
        schema.set("required", required);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        String typeRaw = orNull(input.path("entity_type").asText(null));
        if (typeRaw == null) {
            return ToolResult.error("traverse_entity requires entity_type (project, activity, wbs_node, dpr, issue, supervisor).");
        }
        String type = typeRaw.toLowerCase(Locale.ROOT);

        UUID projectId = ctx.projectId();
        if (!"project".equals(type) && projectId == null) {
            return ToolResult.error(
                    "traverse_entity needs a project in scope for non-project entities. Pick a specific project, then re-ask.");
        }
        if (projectId != null
                && !"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }

        UUID entityId = parseUuid(input.path("entity_id"));
        String entityCode = orNull(input.path("entity_code").asText(null));

        return switch (type) {
            case "project"    -> traverseProject(entityId, entityCode);
            case "activity"   -> traverseActivity(entityId, entityCode, projectId);
            case "wbs_node"   -> traverseWbsNode(entityId, entityCode, projectId);
            case "dpr"        -> traverseDpr(entityId, projectId);
            case "issue"      -> traverseIssue(entityId, projectId);
            case "supervisor" -> traverseSupervisor(entityId, entityCode, projectId);
            default -> ToolResult.error(
                    "Unknown entity_type '" + typeRaw
                            + "'. Allowed: project, activity, wbs_node, dpr, issue, supervisor.");
        };
    }

    private ToolResult traverseProject(UUID id, String code) {
        Optional<Project> proj = id != null ? projectRepository.findById(id)
                : (code != null ? projectRepository.findByCode(code) : Optional.empty());
        if (proj.isEmpty()) return ToolResult.error(notFound("project", id, code));
        Project p = proj.get();

        ObjectNode entity = baseEntity("project", p.getId(), p.getCode(), p.getName(),
                p.getStatus() != null ? p.getStatus().name() : null);
        entity.put("project_id", p.getId().toString());
        entity.put("project_code", p.getCode());

        List<Activity> activities = activityRepository.findByProjectId(p.getId());
        List<WbsNode> wbsRoots = wbsRepository.findByProjectIdAndParentIdIsNullOrderBySortOrder(p.getId());
        List<DailyProgressReport> dprs = dprRepository.findByProjectIdOrderByReportDateAscIdAsc(p.getId());
        List<DprIssue> issues = issueRepository.findByProjectIdOrderByOpenedAtDesc(p.getId());

        ArrayNode children = mapper.createArrayNode();
        children.add(countChild("activity", activities.size(),
                sampleActivities(activities.stream()
                        .sorted(Comparator.comparing(Activity::getCode, Comparator.nullsLast(String::compareTo)))
                        .limit(SAMPLE_LIMIT).toList())));
        children.add(countChild("wbs_node", wbsRepository.findByProjectIdOrderBySortOrder(p.getId()).size(),
                sampleWbs(wbsRoots.stream().limit(SAMPLE_LIMIT).toList())));
        children.add(countChild("dpr", dprs.size(),
                sampleDprs(dprs.stream()
                        .sorted(Comparator.comparing(DailyProgressReport::getReportDate,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(SAMPLE_LIMIT).toList())));
        children.add(issueChild(issues));

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("entity", entity);
        wrapper.set("parents", mapper.createArrayNode());
        wrapper.set("children", children);
        wrapper.set("siblings", mapper.createArrayNode());

        Map<String, List<UUID>> links = new HashMap<>();
        links.put("activity", activities.stream().limit(SAMPLE_LIMIT).map(Activity::getId).toList());
        ToolResult.attachLinks(wrapper, links);

        return ToolResult.ok(
                String.format("%s — %d activit%s, %d DPR%s, %d issue%s.",
                        p.getCode(), activities.size(), activities.size() == 1 ? "y" : "ies",
                        dprs.size(), dprs.size() == 1 ? "" : "s",
                        issues.size(), issues.size() == 1 ? "" : "s"),
                wrapper);
    }

    private ToolResult traverseActivity(UUID id, String code, UUID projectId) {
        Optional<Activity> opt = resolveActivity(id, code, projectId);
        if (opt.isEmpty()) return ToolResult.error(notFound("activity", id, code));
        Activity a = opt.get();
        if (!projectId.equals(a.getProjectId())) return outOfScope("activity");

        ObjectNode entity = baseEntity("activity", a.getId(), a.getCode(), a.getName(),
                a.getStatus() != null ? a.getStatus().name() : null);
        entity.put("percent_complete", a.getPercentComplete());
        entity.put("is_critical", Boolean.TRUE.equals(a.getIsCritical()));
        entity.put("project_id", a.getProjectId().toString());

        ArrayNode parents = mapper.createArrayNode();
        projectRepository.findById(a.getProjectId()).ifPresent(p ->
                parents.add(parentRef("project", p.getId(), p.getCode(), p.getName())));
        if (a.getWbsNodeId() != null) {
            wbsRepository.findById(a.getWbsNodeId()).ifPresent(w ->
                    parents.add(parentRef("wbs_node", w.getId(), w.getCode(), w.getName())));
        }

        List<DailyProgressReport> dprs = dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId).stream()
                .filter(d -> matchesActivity(d.getActivityId(), d.getActivityName(), a))
                .toList();
        List<DprIssue> issues = issueRepository.findByProjectIdOrderByOpenedAtDesc(projectId).stream()
                .filter(i -> matchesActivity(i.getActivityId(), i.getActivityName(), a))
                .toList();
        List<ActivityRelationship> preds = relationshipRepository.findBySuccessorActivityId(a.getId());
        List<ActivityRelationship> succs = relationshipRepository.findByPredecessorActivityId(a.getId());

        ArrayNode children = mapper.createArrayNode();
        children.add(countChild("dpr", dprs.size(),
                sampleDprs(dprs.stream()
                        .sorted(Comparator.comparing(DailyProgressReport::getReportDate,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(SAMPLE_LIMIT).toList())));
        children.add(issueChild(issues));
        children.add(relationshipChild("predecessor", preds, ActivityRelationship::getPredecessorActivityId));
        children.add(relationshipChild("successor", succs, ActivityRelationship::getSuccessorActivityId));

        if (a.getResponsibleResourceId() != null) {
            ObjectNode sup = mapper.createObjectNode();
            sup.put("type", "supervisor");
            sup.put("count", 1);
            ArrayNode arr = mapper.createArrayNode();
            ObjectNode s = mapper.createObjectNode();
            s.put("id", a.getResponsibleResourceId().toString());
            s.put("name", a.getResponsibleResourceName());
            arr.add(s);
            sup.set("sample", arr);
            children.add(sup);
        }

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("entity", entity);
        wrapper.set("parents", parents);
        wrapper.set("children", children);
        wrapper.set("siblings", mapper.createArrayNode());

        Map<String, List<UUID>> links = new HashMap<>();
        if (!dprs.isEmpty()) {
            links.put("dpr", dprs.stream().limit(SAMPLE_LIMIT).map(DailyProgressReport::getId).toList());
        }
        if (!issues.isEmpty()) {
            links.put("issue", issues.stream().limit(SAMPLE_LIMIT).map(DprIssue::getId).toList());
        }
        if (a.getResponsibleResourceId() != null) {
            links.put("supervisor", List.of(a.getResponsibleResourceId()));
        }
        ToolResult.attachLinks(wrapper, links);

        int openIssues = (int) issues.stream().filter(i -> isOpen(i.getStatus())).count();
        return ToolResult.ok(
                String.format("%s (%s) — %d DPR%s, %d issue%s (%d open), %d predecessor%s, %d successor%s.",
                        a.getCode(), a.getName(),
                        dprs.size(), dprs.size() == 1 ? "" : "s",
                        issues.size(), issues.size() == 1 ? "" : "s",
                        openIssues,
                        preds.size(), preds.size() == 1 ? "" : "s",
                        succs.size(), succs.size() == 1 ? "" : "s"),
                wrapper);
    }

    private ToolResult traverseWbsNode(UUID id, String code, UUID projectId) {
        Optional<WbsNode> opt = id != null ? wbsRepository.findById(id)
                : (code != null ? wbsRepository.findByProjectIdAndCode(projectId, code) : Optional.empty());
        if (opt.isEmpty()) return ToolResult.error(notFound("wbs_node", id, code));
        WbsNode w = opt.get();
        if (!projectId.equals(w.getProjectId())) return outOfScope("wbs_node");

        ObjectNode entity = baseEntity("wbs_node", w.getId(), w.getCode(), w.getName(),
                w.getWbsStatus() != null ? w.getWbsStatus().name() : null);
        entity.put("project_id", w.getProjectId().toString());
        if (w.getWbsLevel() != null) entity.put("level", w.getWbsLevel());

        ArrayNode parents = mapper.createArrayNode();
        projectRepository.findById(w.getProjectId()).ifPresent(p ->
                parents.add(parentRef("project", p.getId(), p.getCode(), p.getName())));
        if (w.getParentId() != null) {
            wbsRepository.findById(w.getParentId()).ifPresent(pw ->
                    parents.add(parentRef("wbs_node", pw.getId(), pw.getCode(), pw.getName())));
        }

        List<WbsNode> children = wbsRepository.findByParentIdOrderBySortOrder(w.getId());
        List<Activity> activities = activityRepository.findByWbsNodeId(w.getId());

        ArrayNode childArr = mapper.createArrayNode();
        childArr.add(countChild("wbs_node", children.size(), sampleWbs(children.stream().limit(SAMPLE_LIMIT).toList())));
        childArr.add(countChild("activity", activities.size(),
                sampleActivities(activities.stream().limit(SAMPLE_LIMIT).toList())));

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("entity", entity);
        wrapper.set("parents", parents);
        wrapper.set("children", childArr);
        wrapper.set("siblings", mapper.createArrayNode());

        Map<String, List<UUID>> links = new HashMap<>();
        if (!activities.isEmpty()) {
            links.put("activity", activities.stream().limit(SAMPLE_LIMIT).map(Activity::getId).toList());
        }
        ToolResult.attachLinks(wrapper, links);

        return ToolResult.ok(
                String.format("WBS %s (%s) — %d child node%s, %d activit%s.",
                        w.getCode(), w.getName(),
                        children.size(), children.size() == 1 ? "" : "s",
                        activities.size(), activities.size() == 1 ? "y" : "ies"),
                wrapper);
    }

    private ToolResult traverseDpr(UUID id, UUID projectId) {
        if (id == null) return ToolResult.error("traverse_entity for dpr requires entity_id (UUID).");
        Optional<DailyProgressReport> opt = dprRepository.findById(id);
        if (opt.isEmpty()) return ToolResult.error(notFound("dpr", id, null));
        DailyProgressReport d = opt.get();
        if (!projectId.equals(d.getProjectId())) return outOfScope("dpr");

        ObjectNode entity = baseEntity("dpr", d.getId(), null, d.getActivityName(),
                d.getReportDate() != null ? d.getReportDate().toString() : null);
        entity.put("project_id", d.getProjectId().toString());
        if (d.getReportDate() != null) entity.put("report_date", d.getReportDate().toString());
        entity.put("supervisor_name", d.getSupervisorName());
        if (d.getSupervisorUserId() != null) {
            entity.put("supervisor_user_id", d.getSupervisorUserId().toString());
        }

        ArrayNode parents = mapper.createArrayNode();
        projectRepository.findById(d.getProjectId()).ifPresent(p ->
                parents.add(parentRef("project", p.getId(), p.getCode(), p.getName())));
        Activity parentActivity = null;
        if (d.getActivityId() != null) {
            parentActivity = activityRepository.findById(d.getActivityId()).orElse(null);
        }
        if (parentActivity != null) {
            parents.add(parentRef("activity", parentActivity.getId(),
                    parentActivity.getCode(), parentActivity.getName()));
        }

        List<DprIssue> issues = issueRepository.findByDprIdOrderByOpenedAtAsc(d.getId());

        ArrayNode children = mapper.createArrayNode();
        children.add(issueChild(issues));

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("entity", entity);
        wrapper.set("parents", parents);
        wrapper.set("children", children);
        wrapper.set("siblings", mapper.createArrayNode());

        Map<String, List<UUID>> links = new HashMap<>();
        if (parentActivity != null) links.put("activity", List.of(parentActivity.getId()));
        if (!issues.isEmpty()) {
            links.put("issue", issues.stream().limit(SAMPLE_LIMIT).map(DprIssue::getId).toList());
        }
        if (d.getSupervisorUserId() != null) {
            links.put("supervisor", List.of(d.getSupervisorUserId()));
        }
        ToolResult.attachLinks(wrapper, links);

        return ToolResult.ok(
                String.format("DPR on %s for %s — %d issue%s.",
                        d.getReportDate(), d.getActivityName(),
                        issues.size(), issues.size() == 1 ? "" : "s"),
                wrapper);
    }

    private ToolResult traverseIssue(UUID id, UUID projectId) {
        if (id == null) return ToolResult.error("traverse_entity for issue requires entity_id (UUID).");
        Optional<DprIssue> opt = issueRepository.findById(id);
        if (opt.isEmpty()) return ToolResult.error(notFound("issue", id, null));
        DprIssue i = opt.get();
        if (!projectId.equals(i.getProjectId())) return outOfScope("issue");

        ObjectNode entity = baseEntity("issue", i.getId(), null, i.getTitle(),
                i.getStatus() != null ? i.getStatus().name() : null);
        entity.put("project_id", i.getProjectId().toString());
        if (i.getSeverity() != null) entity.put("severity", i.getSeverity().name());
        if (i.getCategory() != null) entity.put("category", i.getCategory().name());
        if (i.getReportDate() != null) entity.put("report_date", i.getReportDate().toString());
        entity.put("supervisor_name", i.getSupervisorName());
        entity.put("assigned_to_name", i.getAssignedToName());

        ArrayNode parents = mapper.createArrayNode();
        projectRepository.findById(i.getProjectId()).ifPresent(p ->
                parents.add(parentRef("project", p.getId(), p.getCode(), p.getName())));
        if (i.getDprId() != null) {
            dprRepository.findById(i.getDprId()).ifPresent(d -> {
                ObjectNode ref = mapper.createObjectNode();
                ref.put("type", "dpr");
                ref.put("id", d.getId().toString());
                ref.put("report_date", d.getReportDate() != null ? d.getReportDate().toString() : null);
                ref.put("activity_name", d.getActivityName());
                parents.add(ref);
            });
        }
        if (i.getActivityId() != null) {
            activityRepository.findById(i.getActivityId()).ifPresent(a ->
                    parents.add(parentRef("activity", a.getId(), a.getCode(), a.getName())));
        }

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("entity", entity);
        wrapper.set("parents", parents);
        wrapper.set("children", mapper.createArrayNode());
        wrapper.set("siblings", mapper.createArrayNode());

        Map<String, List<UUID>> links = new HashMap<>();
        if (i.getDprId() != null) links.put("dpr", List.of(i.getDprId()));
        if (i.getActivityId() != null) links.put("activity", List.of(i.getActivityId()));
        if (i.getSupervisorResourceId() != null) {
            links.put("supervisor", List.of(i.getSupervisorResourceId()));
        }
        ToolResult.attachLinks(wrapper, links);

        return ToolResult.ok(
                String.format("Issue '%s' (%s, %s)", i.getTitle(),
                        i.getSeverity() != null ? i.getSeverity().name() : "?",
                        i.getStatus() != null ? i.getStatus().name() : "?"),
                wrapper);
    }

    private ToolResult traverseSupervisor(UUID id, String code, UUID projectId) {
        Optional<Resource> opt = id != null ? resourceRepository.findById(id)
                : (code != null ? resourceRepository.findByCode(code) : Optional.empty());
        if (opt.isEmpty()) return ToolResult.error(notFound("supervisor", id, code));
        Resource r = opt.get();

        ObjectNode entity = baseEntity("supervisor", r.getId(), r.getCode(), r.getName(),
                r.getStatus() != null ? r.getStatus().name() : null);

        List<Activity> supervised =
                activityRepository.findByProjectIdAndResponsibleResourceId(projectId, r.getId());

        List<DailyProgressReport> dprs = dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId).stream()
                .filter(d -> r.getId().equals(d.getSupervisorUserId()))
                .toList();
        List<DprIssue> issuesLogged = issueRepository.findByProjectIdOrderByOpenedAtDesc(projectId).stream()
                .filter(i -> r.getId().equals(i.getSupervisorResourceId()))
                .toList();
        List<Resource> subordinates = resourceRepository.findByParentId(r.getId());

        ArrayNode children = mapper.createArrayNode();
        children.add(countChild("activity", supervised.size(),
                sampleActivities(supervised.stream().limit(SAMPLE_LIMIT).toList())));
        children.add(countChild("dpr", dprs.size(),
                sampleDprs(dprs.stream()
                        .sorted(Comparator.comparing(DailyProgressReport::getReportDate,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(SAMPLE_LIMIT).toList())));
        children.add(issueChild(issuesLogged));
        children.add(countChild("subordinate", subordinates.size(),
                sampleResources(subordinates.stream().limit(SAMPLE_LIMIT).toList())));

        ArrayNode parents = mapper.createArrayNode();
        if (r.getParentId() != null) {
            resourceRepository.findById(r.getParentId()).ifPresent(p ->
                    parents.add(parentRef("supervisor", p.getId(), p.getCode(), p.getName())));
        }

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("entity", entity);
        wrapper.set("parents", parents);
        wrapper.set("children", children);
        wrapper.set("siblings", mapper.createArrayNode());

        Map<String, List<UUID>> links = new HashMap<>();
        if (!supervised.isEmpty()) {
            links.put("activity", supervised.stream().limit(SAMPLE_LIMIT).map(Activity::getId).toList());
        }
        if (!dprs.isEmpty()) {
            links.put("dpr", dprs.stream().limit(SAMPLE_LIMIT).map(DailyProgressReport::getId).toList());
        }
        ToolResult.attachLinks(wrapper, links);

        return ToolResult.ok(
                String.format("Supervisor %s (%s) — %d activit%s, %d DPR%s, %d issue%s logged, %d direct report%s.",
                        r.getName(), r.getCode(),
                        supervised.size(), supervised.size() == 1 ? "y" : "ies",
                        dprs.size(), dprs.size() == 1 ? "" : "s",
                        issuesLogged.size(), issuesLogged.size() == 1 ? "" : "s",
                        subordinates.size(), subordinates.size() == 1 ? "" : "s"),
                wrapper);
    }

    // ---------- helpers ----------

    private Optional<Activity> resolveActivity(UUID id, String code, UUID projectId) {
        if (id != null) return activityRepository.findById(id);
        if (code != null && projectId != null) return activityRepository.findByProjectIdAndCode(projectId, code);
        return Optional.empty();
    }

    private static boolean matchesActivity(UUID activityId, String activityName, Activity a) {
        if (activityId != null && activityId.equals(a.getId())) return true;
        if (activityName == null || a.getName() == null) return false;
        return activityName.equalsIgnoreCase(a.getName());
    }

    private static boolean isOpen(IssueStatus status) {
        return status != null && !status.resolvedAtTerminal() && status != IssueStatus.CANCELLED;
    }

    private ObjectNode baseEntity(String type, UUID id, String code, String name, String summary) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", type);
        n.put("id", id != null ? id.toString() : null);
        n.put("code", code);
        n.put("name", name);
        if (summary != null) n.put("summary", summary);
        return n;
    }

    private ObjectNode parentRef(String type, UUID id, String code, String name) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", type);
        n.put("id", id != null ? id.toString() : null);
        n.put("code", code);
        n.put("name", name);
        return n;
    }

    private ObjectNode countChild(String type, int count, ArrayNode sample) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", type);
        n.put("count", count);
        n.set("sample", sample);
        return n;
    }

    private ObjectNode issueChild(List<DprIssue> issues) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "issue");
        n.put("count", issues.size());
        int open = 0;
        Map<String, Integer> bySev = new LinkedHashMap<>();
        Map<String, Integer> byCat = new LinkedHashMap<>();
        for (DprIssue i : issues) {
            if (isOpen(i.getStatus())) open++;
            String sev = i.getSeverity() != null ? i.getSeverity().name() : "MEDIUM";
            bySev.merge(sev, 1, Integer::sum);
            String cat = i.getCategory() != null ? i.getCategory().name() : "OTHER";
            byCat.merge(cat, 1, Integer::sum);
        }
        n.put("open_count", open);
        n.set("by_severity", asMap(bySev));
        n.set("by_category", asMap(byCat));
        ArrayNode sample = mapper.createArrayNode();
        issues.stream().limit(SAMPLE_LIMIT).forEach(i -> {
            ObjectNode r = mapper.createObjectNode();
            r.put("id", i.getId() != null ? i.getId().toString() : null);
            r.put("title", i.getTitle());
            r.put("status", i.getStatus() != null ? i.getStatus().name() : null);
            r.put("severity", i.getSeverity() != null ? i.getSeverity().name() : null);
            r.put("category", i.getCategory() != null ? i.getCategory().name() : null);
            r.put("activity_name", i.getActivityName());
            sample.add(r);
        });
        n.set("sample", sample);
        return n;
    }

    private ObjectNode relationshipChild(String label, List<ActivityRelationship> rels,
                                         java.util.function.Function<ActivityRelationship, UUID> idExtractor) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", label);
        n.put("count", rels.size());
        ArrayNode sample = mapper.createArrayNode();
        rels.stream().limit(SAMPLE_LIMIT).forEach(rel -> {
            UUID otherId = idExtractor.apply(rel);
            ObjectNode r = mapper.createObjectNode();
            r.put("activity_id", otherId != null ? otherId.toString() : null);
            r.put("relationship_type", rel.getRelationshipType() != null
                    ? rel.getRelationshipType().name() : null);
            r.put("lag", rel.getLag());
            sample.add(r);
        });
        n.set("sample", sample);
        return n;
    }

    private ObjectNode asMap(Map<String, Integer> counts) {
        ObjectNode n = mapper.createObjectNode();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> n.put(e.getKey(), e.getValue()));
        return n;
    }

    private ArrayNode sampleActivities(List<Activity> activities) {
        ArrayNode arr = mapper.createArrayNode();
        for (Activity a : activities) {
            ObjectNode n = mapper.createObjectNode();
            n.put("id", a.getId() != null ? a.getId().toString() : null);
            n.put("code", a.getCode());
            n.put("name", a.getName());
            n.put("status", a.getStatus() != null ? a.getStatus().name() : null);
            arr.add(n);
        }
        return arr;
    }

    private ArrayNode sampleWbs(List<WbsNode> nodes) {
        ArrayNode arr = mapper.createArrayNode();
        for (WbsNode w : nodes) {
            ObjectNode n = mapper.createObjectNode();
            n.put("id", w.getId() != null ? w.getId().toString() : null);
            n.put("code", w.getCode());
            n.put("name", w.getName());
            arr.add(n);
        }
        return arr;
    }

    private ArrayNode sampleDprs(List<DailyProgressReport> dprs) {
        ArrayNode arr = mapper.createArrayNode();
        for (DailyProgressReport d : dprs) {
            ObjectNode n = mapper.createObjectNode();
            n.put("id", d.getId() != null ? d.getId().toString() : null);
            n.put("report_date", d.getReportDate() != null ? d.getReportDate().toString() : null);
            n.put("activity_name", d.getActivityName());
            n.put("supervisor_name", d.getSupervisorName());
            arr.add(n);
        }
        return arr;
    }

    private ArrayNode sampleResources(List<Resource> resources) {
        ArrayNode arr = mapper.createArrayNode();
        for (Resource r : resources) {
            ObjectNode n = mapper.createObjectNode();
            n.put("id", r.getId() != null ? r.getId().toString() : null);
            n.put("code", r.getCode());
            n.put("name", r.getName());
            arr.add(n);
        }
        return arr;
    }

    private static String notFound(String type, UUID id, String code) {
        return type + " not found (id=" + id + ", code=" + code + ").";
    }

    private static ToolResult outOfScope(String type) {
        return ToolResult.error(type + " is not part of the current project scope. Re-issue with the project pinned.");
    }

    private static String orNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static UUID parseUuid(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String s = node.asText(null);
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s.trim()); } catch (IllegalArgumentException e) { return null; }
    }

    @Override
    public Set<String> allowedRoles() {
        return Set.of(
                "PROJECT_MANAGER", "PORTFOLIO_MANAGER",
                "SITE_MANAGER", "PROJECT_ENGINEER", "QC_MANAGER", "QA_QC_ENGINEER",
                "BIM_DATA_COORDINATOR",
                "SITE_ENGINEER", "RESOURCE_MANAGER", "SCHEDULER",
                "EXECUTIVE_VIEWER");
    }
}
