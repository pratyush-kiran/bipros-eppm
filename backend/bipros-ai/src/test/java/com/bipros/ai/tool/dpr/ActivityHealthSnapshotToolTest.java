package com.bipros.ai.tool.dpr;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.testsupport.AiContextFixtures;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprIssue;
import com.bipros.project.domain.model.IssueCategory;
import com.bipros.project.domain.model.IssueSeverity;
import com.bipros.project.domain.model.IssueStatus;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprIssueRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityHealthSnapshotTool — per-activity DPR + issue rollup")
class ActivityHealthSnapshotToolTest {

    @Mock private ActivityRepository activityRepository;
    @Mock private DailyProgressReportRepository dprRepository;
    @Mock private DprIssueRepository issueRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID projectId = UUID.randomUUID();
    private ActivityHealthSnapshotTool tool;

    @BeforeEach
    void setUp() {
        tool = new ActivityHealthSnapshotTool(activityRepository, dprRepository, issueRepository, mapper);
    }

    @Test
    @DisplayName("ROAD-001 scenario: 2 activities, 1 DPR, 1 issue on Foundation Excavation")
    void roadOneScenario() {
        // mirrors the screenshot: ACT-001 (Site Survey) + ACT-002 (Foundation Excavation) with 1 DPR + 1 issue on ACT-002
        Activity act1 = activity("ACT-001", "Site Survey and Marking");
        Activity act2 = activity("ACT-002", "Foundation Excavation");
        when(activityRepository.findByProjectId(projectId)).thenReturn(List.of(act1, act2));

        DailyProgressReport dpr = dpr(act2, LocalDate.of(2026, 5, 12), "Care Taker");
        when(dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId)).thenReturn(List.of(dpr));

        DprIssue issue = issue(act2, dpr.getId(),
                IssueCategory.MATERIAL_SHORTAGE, IssueSeverity.HIGH, IssueStatus.OPEN);
        when(issueRepository.findByProjectIdOrderByOpenedAtDesc(projectId)).thenReturn(List.of(issue));

        AiContext ctx = AiContextFixtures.forProfile("PROJECT_MANAGER", projectId);
        ToolResult result = tool.execute(mapper.createObjectNode(), ctx);

        assertThat(result.success()).isTrue();
        JsonNode data = result.data();
        JsonNode rollup = data.get("rollup");
        assertThat(rollup.get("total_activities").asInt()).isEqualTo(2);
        assertThat(rollup.get("activities_with_issues").asInt()).isEqualTo(1);
        assertThat(rollup.get("total_issues").asInt()).isEqualTo(1);
        assertThat(rollup.get("open_issues").asInt()).isEqualTo(1);
        assertThat(rollup.get("top_category_across_project").asText()).isEqualTo("MATERIAL_SHORTAGE");

        // ACT-002 should come first (busiest by issues)
        JsonNode first = data.get("activities").get(0);
        assertThat(first.get("code").asText()).isEqualTo("ACT-002");
        assertThat(first.get("dpr_count").asInt()).isEqualTo(1);
        assertThat(first.get("latest_dpr_date").asText()).isEqualTo("2026-05-12");
        assertThat(first.get("supervisor_name").asText()).isEqualTo("Care Taker");
        assertThat(first.get("issues").get("total").asInt()).isEqualTo(1);
        assertThat(first.get("issues").get("open").asInt()).isEqualTo(1);
        assertThat(first.get("issues").get("top_category").asText()).isEqualTo("MATERIAL_SHORTAGE");

        // ACT-001 second, zero everything
        JsonNode second = data.get("activities").get(1);
        assertThat(second.get("code").asText()).isEqualTo("ACT-001");
        assertThat(second.get("dpr_count").asInt()).isZero();
        assertThat(second.get("issues").get("total").asInt()).isZero();
    }

    @Test
    @DisplayName("CANCELLED issues excluded by default; include_cancelled=true brings them in")
    void cancelledHandling() {
        Activity a = activity("ACT-100", "Bench Cutting");
        when(activityRepository.findByProjectId(projectId)).thenReturn(List.of(a));
        when(dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId)).thenReturn(List.of());
        when(issueRepository.findByProjectIdOrderByOpenedAtDesc(projectId)).thenReturn(List.of(
                issue(a, UUID.randomUUID(), IssueCategory.WEATHER, IssueSeverity.LOW, IssueStatus.OPEN),
                issue(a, UUID.randomUUID(), IssueCategory.OTHER, IssueSeverity.LOW, IssueStatus.CANCELLED)));

        AiContext ctx = AiContextFixtures.forProfile("PROJECT_MANAGER", projectId);
        ToolResult defaultResult = tool.execute(mapper.createObjectNode(), ctx);
        assertThat(defaultResult.data().get("rollup").get("total_issues").asInt()).isEqualTo(1);

        ObjectNode input = mapper.createObjectNode();
        input.put("include_cancelled", true);
        ToolResult included = tool.execute(input, ctx);
        assertThat(included.data().get("rollup").get("total_issues").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("activity_codes filter narrows result, DPRs / issues for other activities are ignored")
    void activityCodeFilter() {
        Activity a1 = activity("ACT-100", "Excavation");
        Activity a2 = activity("ACT-200", "Asphalting");
        when(activityRepository.findByProjectId(projectId)).thenReturn(List.of(a1, a2));
        when(dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId))
                .thenReturn(List.of(dpr(a1, LocalDate.now(), "Mohd"), dpr(a2, LocalDate.now(), "Ravi")));
        when(issueRepository.findByProjectIdOrderByOpenedAtDesc(projectId))
                .thenReturn(List.of(issue(a2, UUID.randomUUID(),
                        IssueCategory.MATERIAL_SHORTAGE, IssueSeverity.HIGH, IssueStatus.OPEN)));

        ObjectNode input = mapper.createObjectNode();
        input.putArray("activity_codes").add("ACT-200");

        ToolResult result = tool.execute(input, AiContextFixtures.forProfile("SITE_MANAGER", projectId));
        JsonNode data = result.data();
        assertThat(data.get("activities")).hasSize(1);
        assertThat(data.get("activities").get(0).get("code").asText()).isEqualTo("ACT-200");
        assertThat(data.get("rollup").get("total_issues").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("no project in scope ⇒ structured error, no JPA calls")
    void noProjectScopeError() {
        AiContext noProject = new AiContext(UUID.randomUUID(), null, "dpr",
                "PROJECT_MANAGER", "PROJECT_MANAGER", List.of());
        ToolResult result = tool.execute(mapper.createObjectNode(), noProject);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("project in scope");
    }

    private Activity activity(String code, String name) {
        Activity a = new Activity();
        a.setId(UUID.randomUUID());
        a.setProjectId(projectId);
        a.setCode(code);
        a.setName(name);
        a.setStatus(ActivityStatus.IN_PROGRESS);
        a.setPercentComplete(0.0);
        a.setIsCritical(false);
        return a;
    }

    private DailyProgressReport dpr(Activity activity, LocalDate date, String supervisor) {
        DailyProgressReport d = new DailyProgressReport();
        d.setId(UUID.randomUUID());
        d.setProjectId(projectId);
        d.setReportDate(date);
        d.setActivityId(activity.getId());
        d.setActivityName(activity.getName());
        d.setSupervisorName(supervisor);
        d.setSupervisorUserId(UUID.randomUUID());
        d.setUnit("Cum");
        d.setQtyExecuted(BigDecimal.valueOf(210));
        return d;
    }

    private DprIssue issue(Activity activity, UUID dprId,
                           IssueCategory cat, IssueSeverity sev, IssueStatus status) {
        DprIssue i = DprIssue.builder()
                .dprId(dprId)
                .projectId(projectId)
                .activityId(activity.getId())
                .activityName(activity.getName())
                .reportDate(LocalDate.now())
                .openedAt(Instant.now())
                .category(cat)
                .severity(sev)
                .status(status)
                .title("test")
                .build();
        i.setId(UUID.randomUUID());
        return i;
    }
}
