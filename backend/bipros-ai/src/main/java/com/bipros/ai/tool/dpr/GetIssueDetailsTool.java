package com.bipros.ai.tool.dpr;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprIssue;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprIssueRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Drill into a single DPR-issue. Returns the issue row plus a snapshot of the parent DPR
 * (date, supervisor, activity, chainage) so the LLM has the full context without an extra
 * tool round.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetIssueDetailsTool implements Tool {

    private final DprIssueRepository issueRepository;
    private final DailyProgressReportRepository dprRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_issue_details";
    }

    @Override
    public String description() {
        return "Fetch a single DPR-issue by id, with the parent DPR snapshot (date, supervisor, "
                + "activity, chainage). Use AFTER list_issues surfaces an interesting row, or when "
                + "the user names a specific issue id. Returns the issue's title, description, "
                + "category (reason), severity, status, supervisor (who logged it), assignee "
                + "(\"who is looking into it\"), opened_at, resolved_at, age_hours, and the linked "
                + "DPR's date / chainage / activity. Project-scoped.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("issue_id", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "UUID of the issue to fetch."));
        schema.set("properties", props);
        schema.set("required", objectMapper.createArrayNode().add("issue_id"));
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error(
                    "get_issue_details needs a project in scope. Pick a specific project, then re-ask.");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }

        String idStr = input.path("issue_id").asText(null);
        UUID issueId;
        try {
            issueId = UUID.fromString(idStr);
        } catch (Exception e) {
            return ToolResult.error("issue_id must be a UUID");
        }
        Optional<DprIssue> opt = issueRepository.findByIdAndProjectId(issueId, projectId);
        if (opt.isEmpty()) {
            return ToolResult.error("Issue " + issueId + " not found in this project.");
        }
        DprIssue i = opt.get();

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.put("issue_id", i.getId().toString());
        wrapper.put("dpr_id", i.getDprId() != null ? i.getDprId().toString() : null);
        wrapper.put("activity_id", i.getActivityId() != null ? i.getActivityId().toString() : null);
        wrapper.put("activity_name", i.getActivityName());
        wrapper.put("supervisor_resource_id",
                i.getSupervisorResourceId() != null ? i.getSupervisorResourceId().toString() : null);
        wrapper.put("supervisor_name", i.getSupervisorName());
        wrapper.put("assigned_to_resource_id",
                i.getAssignedToResourceId() != null ? i.getAssignedToResourceId().toString() : null);
        wrapper.put("assigned_to_name", i.getAssignedToName());
        wrapper.put("title", i.getTitle());
        wrapper.put("description", i.getDescription());
        wrapper.put("category", i.getCategory() != null ? i.getCategory().name() : null);
        wrapper.put("severity", i.getSeverity() != null ? i.getSeverity().name() : null);
        wrapper.put("status", i.getStatus() != null ? i.getStatus().name() : null);
        wrapper.put("report_date", i.getReportDate() != null ? i.getReportDate().toString() : null);
        wrapper.put("opened_at", i.getOpenedAt() != null ? i.getOpenedAt().toString() : null);
        wrapper.put("resolved_at", i.getResolvedAt() != null ? i.getResolvedAt().toString() : null);
        wrapper.put("age_hours", ageHours(i));
        wrapper.put("resolution_notes", i.getResolutionNotes());
        wrapper.put("chainage_from_m", i.getChainageFromM());
        wrapper.put("chainage_to_m", i.getChainageToM());

        if (i.getDprId() != null) {
            dprRepository.findById(i.getDprId()).ifPresent(dpr -> {
                ObjectNode parent = objectMapper.createObjectNode();
                parent.put("dpr_id", dpr.getId().toString());
                parent.put("report_date", dpr.getReportDate() != null ? dpr.getReportDate().toString() : null);
                parent.put("activity_name", dpr.getActivityName());
                parent.put("supervisor_name", dpr.getSupervisorName());
                parent.put("approval_status", dpr.getApprovalStatus() != null ? dpr.getApprovalStatus().name() : null);
                wrapper.set("parent_dpr", parent);
            });
        }

        Map<String, List<UUID>> links = new HashMap<>();
        if (i.getActivityId() != null) links.put("activity", List.of(i.getActivityId()));
        if (i.getSupervisorResourceId() != null) {
            links.put("supervisor", List.of(i.getSupervisorResourceId()));
        }
        if (i.getDprId() != null) links.put("dpr", List.of(i.getDprId()));
        ToolResult.attachLinks(wrapper, links);

        String summary = String.format(
                "Issue \"%s\" — %s / %s%s — supervisor %s, assigned %s.",
                i.getTitle(),
                i.getSeverity() != null ? i.getSeverity().name() : "?",
                i.getStatus() != null ? i.getStatus().name() : "?",
                i.getActivityName() != null ? " on " + i.getActivityName() : "",
                i.getSupervisorName() != null ? i.getSupervisorName() : "(unset)",
                i.getAssignedToName() != null ? i.getAssignedToName() : "(unset)");
        return ToolResult.ok(summary, wrapper);
    }

    private static Double ageHours(DprIssue i) {
        if (i.getOpenedAt() == null) return null;
        Instant end = i.getResolvedAt() != null ? i.getResolvedAt() : Instant.now();
        return Duration.between(i.getOpenedAt(), end).toMillis() / 3_600_000.0;
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
