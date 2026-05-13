package com.bipros.ai.tool.graph;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.testsupport.AiContextFixtures;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprIssue;
import com.bipros.project.domain.model.IssueCategory;
import com.bipros.project.domain.model.IssueSeverity;
import com.bipros.project.domain.model.IssueStatus;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.ProjectStatus;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprIssueRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TraverseEntityTool — graph walk over project domain")
class TraverseEntityToolTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ActivityRelationshipRepository relationshipRepository;
    @Mock private DailyProgressReportRepository dprRepository;
    @Mock private DprIssueRepository issueRepository;
    @Mock private WbsNodeRepository wbsRepository;
    @Mock private ResourceRepository resourceRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID projectId = UUID.randomUUID();
    private TraverseEntityTool tool;

    @BeforeEach
    void setUp() {
        tool = new TraverseEntityTool(projectRepository, activityRepository, relationshipRepository,
                dprRepository, issueRepository, wbsRepository, resourceRepository, mapper);
    }

    @Test
    @DisplayName("activity branch returns DPR + issue child counts with the parent activity link")
    void traverseActivity() {
        Activity a = activity("ACT-002", "Foundation Excavation");
        when(activityRepository.findByProjectIdAndCode(projectId, "ACT-002"))
                .thenReturn(Optional.of(a));
        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(project("ROAD-001", "Road Construction Project")));

        DailyProgressReport d = dpr(a, LocalDate.of(2026, 5, 12), "Care Taker");
        when(dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId))
                .thenReturn(List.of(d));

        DprIssue i = issue(a, d.getId(),
                IssueCategory.MATERIAL_SHORTAGE, IssueSeverity.HIGH, IssueStatus.OPEN);
        when(issueRepository.findByProjectIdOrderByOpenedAtDesc(projectId))
                .thenReturn(List.of(i));
        when(relationshipRepository.findBySuccessorActivityId(a.getId())).thenReturn(List.of());
        when(relationshipRepository.findByPredecessorActivityId(a.getId())).thenReturn(List.of());

        ObjectNode input = mapper.createObjectNode();
        input.put("entity_type", "activity");
        input.put("entity_code", "ACT-002");
        AiContext ctx = AiContextFixtures.forProfile("PROJECT_MANAGER", projectId);
        ToolResult result = tool.execute(input, ctx);

        assertThat(result.success()).isTrue();
        JsonNode data = result.data();
        assertThat(data.get("entity").get("code").asText()).isEqualTo("ACT-002");

        JsonNode parents = data.get("parents");
        assertThat(parents).hasSize(1);
        assertThat(parents.get(0).get("type").asText()).isEqualTo("project");
        assertThat(parents.get(0).get("code").asText()).isEqualTo("ROAD-001");

        JsonNode children = data.get("children");
        boolean hasDpr = false, hasIssue = false;
        for (JsonNode c : children) {
            if ("dpr".equals(c.get("type").asText())) {
                hasDpr = true;
                assertThat(c.get("count").asInt()).isEqualTo(1);
            }
            if ("issue".equals(c.get("type").asText())) {
                hasIssue = true;
                assertThat(c.get("count").asInt()).isEqualTo(1);
                assertThat(c.get("open_count").asInt()).isEqualTo(1);
                assertThat(c.get("by_category").get("MATERIAL_SHORTAGE").asInt()).isEqualTo(1);
            }
        }
        assertThat(hasDpr).isTrue();
        assertThat(hasIssue).isTrue();

        // linked_entity_ids carries the issue + DPR + supervisor IDs
        JsonNode links = data.get("linked_entity_ids");
        assertThat(links).isNotNull();
        assertThat(links.get("dpr")).isNotNull();
        assertThat(links.get("issue")).isNotNull();
    }

    @Test
    @DisplayName("dpr branch reports parent activity + issues filed under this DPR")
    void traverseDpr() {
        Activity a = activity("ACT-002", "Foundation Excavation");
        DailyProgressReport d = dpr(a, LocalDate.of(2026, 5, 12), "Care Taker");
        DprIssue i = issue(a, d.getId(),
                IssueCategory.WEATHER, IssueSeverity.LOW, IssueStatus.RESOLVED);

        when(dprRepository.findById(d.getId())).thenReturn(Optional.of(d));
        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(project("ROAD-001", "Road Construction Project")));
        when(activityRepository.findById(a.getId())).thenReturn(Optional.of(a));
        when(issueRepository.findByDprIdOrderByOpenedAtAsc(d.getId())).thenReturn(List.of(i));

        ObjectNode input = mapper.createObjectNode();
        input.put("entity_type", "dpr");
        input.put("entity_id", d.getId().toString());

        ToolResult result = tool.execute(input, AiContextFixtures.forProfile("SITE_MANAGER", projectId));

        assertThat(result.success()).isTrue();
        JsonNode parents = result.data().get("parents");
        boolean parentActivityFound = false;
        for (JsonNode p : parents) {
            if ("activity".equals(p.get("type").asText())) {
                parentActivityFound = true;
                assertThat(p.get("code").asText()).isEqualTo("ACT-002");
            }
        }
        assertThat(parentActivityFound).isTrue();

        JsonNode children = result.data().get("children");
        assertThat(children.get(0).get("type").asText()).isEqualTo("issue");
        assertThat(children.get(0).get("count").asInt()).isEqualTo(1);
        assertThat(children.get(0).get("open_count").asInt()).isZero();
    }

    @Test
    @DisplayName("issue branch returns parent DPR + activity references")
    void traverseIssue() {
        Activity a = activity("ACT-002", "Foundation Excavation");
        DailyProgressReport d = dpr(a, LocalDate.of(2026, 5, 12), "Care Taker");
        DprIssue i = issue(a, d.getId(),
                IssueCategory.MATERIAL_SHORTAGE, IssueSeverity.HIGH, IssueStatus.OPEN);

        when(issueRepository.findById(i.getId())).thenReturn(Optional.of(i));
        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(project("ROAD-001", "Road Construction Project")));
        when(dprRepository.findById(d.getId())).thenReturn(Optional.of(d));
        when(activityRepository.findById(a.getId())).thenReturn(Optional.of(a));

        ObjectNode input = mapper.createObjectNode();
        input.put("entity_type", "issue");
        input.put("entity_id", i.getId().toString());

        ToolResult result = tool.execute(input, AiContextFixtures.forProfile("SITE_MANAGER", projectId));

        assertThat(result.success()).isTrue();
        JsonNode parents = result.data().get("parents");
        boolean foundActivity = false;
        boolean foundDpr = false;
        for (JsonNode p : parents) {
            if ("activity".equals(p.get("type").asText())) foundActivity = true;
            if ("dpr".equals(p.get("type").asText())) foundDpr = true;
        }
        assertThat(foundActivity).isTrue();
        assertThat(foundDpr).isTrue();
    }

    @Test
    @DisplayName("project branch reports activity + DPR + issue child counts")
    void traverseProject() {
        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(project("ROAD-001", "Road Construction Project")));
        Activity a1 = activity("ACT-001", "Site Survey and Marking");
        Activity a2 = activity("ACT-002", "Foundation Excavation");
        when(activityRepository.findByProjectId(projectId)).thenReturn(List.of(a1, a2));
        when(wbsRepository.findByProjectIdAndParentIdIsNullOrderBySortOrder(projectId)).thenReturn(List.of());
        when(wbsRepository.findByProjectIdOrderBySortOrder(projectId)).thenReturn(List.of());

        DailyProgressReport d = dpr(a2, LocalDate.of(2026, 5, 12), "Care Taker");
        when(dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId)).thenReturn(List.of(d));

        DprIssue i = issue(a2, d.getId(),
                IssueCategory.MATERIAL_SHORTAGE, IssueSeverity.HIGH, IssueStatus.OPEN);
        when(issueRepository.findByProjectIdOrderByOpenedAtDesc(projectId)).thenReturn(List.of(i));

        ObjectNode input = mapper.createObjectNode();
        input.put("entity_type", "project");
        input.put("entity_id", projectId.toString());

        ToolResult result = tool.execute(input, AiContextFixtures.forProfile("PROJECT_MANAGER", projectId));

        assertThat(result.success()).isTrue();
        JsonNode children = result.data().get("children");
        int activityCount = 0, dprCount = 0, issueCount = 0;
        for (JsonNode c : children) {
            switch (c.get("type").asText()) {
                case "activity" -> activityCount = c.get("count").asInt();
                case "dpr"      -> dprCount = c.get("count").asInt();
                case "issue"    -> issueCount = c.get("count").asInt();
                default -> { /* ignore */ }
            }
        }
        assertThat(activityCount).isEqualTo(2);
        assertThat(dprCount).isEqualTo(1);
        assertThat(issueCount).isEqualTo(1);
    }

    @Test
    @DisplayName("unknown entity_type returns structured error")
    void unknownType() {
        ObjectNode input = mapper.createObjectNode();
        input.put("entity_type", "potato");
        ToolResult result = tool.execute(input,
                AiContextFixtures.forProfile("PROJECT_MANAGER", projectId));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown entity_type");
    }

    @Test
    @DisplayName("missing entity_type returns structured error")
    void missingType() {
        ToolResult result = tool.execute(mapper.createObjectNode(),
                AiContextFixtures.forProfile("PROJECT_MANAGER", projectId));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("entity_type");
    }

    @Test
    @DisplayName("no project in scope ⇒ error for non-project entity types")
    void noProjectScopeError() {
        AiContext noProject = new AiContext(UUID.randomUUID(), null, "dpr",
                "PROJECT_MANAGER", "PROJECT_MANAGER", List.of());
        ObjectNode input = mapper.createObjectNode();
        input.put("entity_type", "activity");
        input.put("entity_code", "ACT-001");
        ToolResult result = tool.execute(input, noProject);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("project in scope");
    }

    // ----- fixtures -----

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

    private Project project(String code, String name) {
        Project p = new Project();
        p.setId(projectId);
        p.setCode(code);
        p.setName(name);
        p.setStatus(ProjectStatus.PLANNED);
        return p;
    }

    private DailyProgressReport dpr(Activity activity, LocalDate date, String supervisor) {
        DailyProgressReport d = new DailyProgressReport();
        d.setId(UUID.randomUUID());
        d.setProjectId(projectId);
        d.setReportDate(date);
        d.setActivityId(activity.getId());
        d.setActivityName(activity.getName());
        d.setSupervisorName(supervisor);
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
                .title("Material not delivered")
                .build();
        i.setId(UUID.randomUUID());
        return i;
    }
}
