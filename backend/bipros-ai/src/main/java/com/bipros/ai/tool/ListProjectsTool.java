package com.bipros.ai.tool;

import com.bipros.ai.context.AiContext;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Discovery tool: enumerates projects the calling user can read. The agent uses
 * this as the entry point for any cross-project ("general" module) question
 * that doesn't already carry a current project_id.
 *
 * Reads from the canonical Postgres source rather than ClickHouse `dim_project`,
 * because dimension sync may lag the transactional state on dev environments.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListProjectsTool extends ProjectScopedTool {

    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "list_projects";
    }

    @Override
    public String description() {
        return "List projects the user can access. Returns project_id, code, name, status, "
                + "planned_start, planned_finish, budget_currency (ISO 4217 — e.g. OMR for "
                + "an Oman project, INR for India, USD for cross-border). Optional `status` "
                + "filter (e.g. ACTIVE). Use this first when the user asks a portfolio / "
                + "cross-project question, AND whenever you are about to quote any cost / "
                + "amount on a project — the project's budget_currency is the canonical "
                + "currency to suffix on every money figure (never assume INR or USD).";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("status", objectMapper.createObjectNode().put("type", "string")
                .put("description", "Optional status filter — e.g. ACTIVE, ON_HOLD, COMPLETED"));
        schema.set("properties", props);
        return schema;
    }

    @Override
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        String statusFilter = input.path("status").asText(null);
        boolean admin = "ADMIN".equals(ctx.role());
        Set<UUID> scope = ctx.scopedProjectIds() == null
                ? Set.of()
                : Set.copyOf(ctx.scopedProjectIds());

        // Admin: full project list (minus archived). Non-admin: intersect with scope.
        List<Project> all = projectRepository.findAllByArchivedAtIsNull();
        List<Project> filtered = all.stream()
                .filter(p -> admin || scope.contains(p.getId()))
                .filter(p -> statusFilter == null || statusFilter.isBlank()
                        || (p.getStatus() != null && p.getStatus().name().equalsIgnoreCase(statusFilter)))
                .limit(500)
                .toList();

        ArrayNode arr = objectMapper.createArrayNode();
        for (Project p : filtered) {
            ObjectNode o = objectMapper.createObjectNode();
            o.put("project_id", p.getId().toString());
            o.put("code", p.getCode());
            o.put("name", p.getName());
            o.put("status", p.getStatus() != null ? p.getStatus().name() : null);
            o.put("planned_start", p.getPlannedStartDate() != null ? p.getPlannedStartDate().toString() : null);
            o.put("planned_finish", p.getPlannedFinishDate() != null ? p.getPlannedFinishDate().toString() : null);
            o.put("budget_currency", p.getBudgetCurrency());
            arr.add(o);
        }

        return ToolResult.table(
                "Found " + filtered.size() + " accessible project" + (filtered.size() == 1 ? "" : "s"),
                arr,
                new String[]{"project_id", "code", "name", "status", "planned_start", "planned_finish", "budget_currency"}
        );
    }
}
