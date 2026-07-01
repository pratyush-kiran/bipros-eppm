package com.bipros.api.integration;

import com.bipros.activity.application.dto.CreateActivityRequest;
import com.bipros.activity.domain.model.ActivitySupervisor;
import com.bipros.activity.domain.repository.ActivitySupervisorRepository;
import com.bipros.api.dto.ActivityProgressGenerationRequest;
import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateBoqItemRequest;
import com.bipros.project.application.dto.CreateEpsNodeRequest;
import com.bipros.project.application.dto.CreateProjectRequest;
import com.bipros.project.application.dto.CreateWbsNodeRequest;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.model.role.ManpowerRoleRate;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.role.ManpowerRoleRateRepository;
import com.bipros.security.application.dto.LoginRequest;
import com.bipros.security.application.dto.RegisterRequest;
import com.bipros.security.domain.model.Role;
import com.bipros.security.domain.model.UserRole;
import com.bipros.security.domain.repository.RoleRepository;
import com.bipros.security.domain.repository.UserRepository;
import com.bipros.security.domain.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for POST /v1/admin/projects/{projectId}/generate-activity-progress.
 *
 * <p>Mirrors EpsIntegrationTest's harness: same @SpringBootTest, @Testcontainers, @ActiveProfiles("test"),
 * PostgreSQLContainer, and TestRestTemplate. Repositories are injected directly for seeding entities
 * that can't easily be created via the public API (ADMIN role, ActivitySupervisor, ResourceAssignment
 * with manpowerRoleRateId), following the SecurityTestFixture pattern.
 *
 * <p>Gate: test-compile must pass; actual execution requires Docker (run in-app).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ActivityProgressGeneration Integration Tests")
class ActivityProgressGenerationIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
      .withDatabaseName("bipros_test")
      .withUsername("postgres")
      .withPassword("postgres");

  @Autowired private TestRestTemplate restTemplate;

  // Repos for direct seeding (DataSeeder is not active in 'test' profile)
  @Autowired private RoleRepository roleRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private UserRoleRepository userRoleRepository;
  @Autowired private ActivitySupervisorRepository activitySupervisorRepository;
  @Autowired private ResourceAssignmentRepository resourceAssignmentRepository;
  @Autowired private ManpowerRoleRateRepository manpowerRoleRateRepository;

  private String adminToken;
  private UUID adminUserId;
  private UUID projectId;
  private UUID activityId;
  private UUID boqItemId;
  private UUID resourceAssignmentId;

  @BeforeEach
  void setUp() {
    String suffix = "APG" + System.currentTimeMillis();
    String username = "apguser" + suffix;

    // 1. Register a user (gets VIEWER role by default)
    RegisterRequest reg = new RegisterRequest(
        username, username + "@example.com", "testPassword123!", "APG", "User");
    restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);

    // 2. Grant ADMIN role via repo (DataSeeder does not run in 'test' profile)
    //    Also seed VIEWER so register() doesn't fail on existing users in other tests.
    roleRepository.findByName("VIEWER")
        .orElseGet(() -> roleRepository.save(new Role("VIEWER", "View-only access")));
    Role adminRole = roleRepository.findByName("ADMIN")
        .orElseGet(() -> roleRepository.save(new Role("ADMIN", "System Administrator")));

    adminUserId = userRepository.findByUsername(username)
        .orElseThrow(() -> new IllegalStateException("User not found: " + username))
        .getId();
    if (!userRoleRepository.existsByUserIdAndRoleId(adminUserId, adminRole.getId())) {
      userRoleRepository.save(new UserRole(adminUserId, adminRole.getId()));
    }

    // 3. Login — new token now includes ROLE_ADMIN
    LoginRequest login = new LoginRequest(username, "testPassword123!");
    ResponseEntity<ApiResponse> loginResp = restTemplate.postForEntity(
        "/v1/auth/login", login, ApiResponse.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> loginData = (Map<String, Object>) loginResp.getBody().data();
    adminToken = (String) loginData.get("accessToken");

    HttpHeaders h = authHeaders();

    // 4. EPS node
    CreateEpsNodeRequest epsReq = new CreateEpsNodeRequest(
        "EPS-APG-" + suffix, "EPS APG " + suffix, null, null);
    ResponseEntity<ApiResponse> epsResp = restTemplate.exchange(
        "/v1/eps", HttpMethod.POST, new HttpEntity<>(epsReq, h), ApiResponse.class);
    @SuppressWarnings("unchecked")
    UUID epsId = UUID.fromString(
        (String) ((Map<String, Object>) epsResp.getBody().data()).get("id"));

    // 5. Project
    CreateProjectRequest projReq = new CreateProjectRequest(
        "PRJ-APG-" + suffix, "Project APG " + suffix, "Progress gen test",
        epsId, null, LocalDate.now().minusMonths(3), LocalDate.now().plusMonths(9),
        5, null, null, null, null, null, null, null, null, null, null);
    ResponseEntity<ApiResponse> projResp = restTemplate.exchange(
        "/v1/projects", HttpMethod.POST, new HttpEntity<>(projReq, h), ApiResponse.class);
    @SuppressWarnings("unchecked")
    UUID epsIdUnused = epsId; // satisfy compiler
    @SuppressWarnings("unchecked")
    Map<String, Object> projData = (Map<String, Object>) projResp.getBody().data();
    projectId = UUID.fromString((String) projData.get("id"));

    // 6. WBS node
    CreateWbsNodeRequest wbsReq = new CreateWbsNodeRequest(
        "WBS-APG-" + suffix, "WBS APG " + suffix, null, projectId, null);
    ResponseEntity<ApiResponse> wbsResp = restTemplate.exchange(
        "/v1/projects/" + projectId + "/wbs",
        HttpMethod.POST, new HttpEntity<>(wbsReq, h), ApiResponse.class);
    @SuppressWarnings("unchecked")
    UUID wbsNodeId = UUID.fromString(
        (String) ((Map<String, Object>) wbsResp.getBody().data()).get("id"));

    // 7. Activity at 0% (default — no DPRs yet)
    // Name matches BOQ description so BoqLinkResolver.listForActivity() finds the item.
    String activityName = "Concrete Pour APG " + suffix;
    CreateActivityRequest actReq = new CreateActivityRequest(
        "ACT-APG-" + suffix, activityName, "Generated by integration test",
        projectId, wbsNodeId, null, null, null,
        10.0, LocalDate.now().minusDays(10), LocalDate.now(),
        null, null, null, null, null, null, null, null);
    ResponseEntity<ApiResponse> actResp = restTemplate.exchange(
        "/v1/projects/" + projectId + "/activities",
        HttpMethod.POST, new HttpEntity<>(actReq, h), ApiResponse.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> actData = (Map<String, Object>) actResp.getBody().data();
    activityId = UUID.fromString((String) actData.get("id"));

    // 8. BOQ item — description == activityName so listForActivity heuristic links them
    CreateBoqItemRequest boqReq = new CreateBoqItemRequest(
        "BOQ-APG-001-" + suffix, activityName, "Cum",
        wbsNodeId,
        new BigDecimal("100"),   // boqQty
        new BigDecimal("500"),   // boqRate (positive, non-zero)
        null, null, null, null, null);
    ResponseEntity<ApiResponse> boqResp = restTemplate.exchange(
        "/v1/projects/" + projectId + "/boq",
        HttpMethod.POST, new HttpEntity<>(boqReq, h), ApiResponse.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> boqData = (Map<String, Object>) boqResp.getBody().data();
    boqItemId = UUID.fromString((String) boqData.get("id"));

    // 9. ActivitySupervisor — admin user is the supervisor
    ActivitySupervisor supervisor = new ActivitySupervisor();
    supervisor.setActivityId(activityId);
    supervisor.setUserId(adminUserId);
    supervisor.setUserNameSnapshot("APG Admin");
    activitySupervisorRepository.save(supervisor);

    // 10. ManpowerRoleRate — no FK constraints on category_id/grade_id in DB (soft refs)
    UUID roleId = UUID.randomUUID();
    ManpowerRoleRate rate = ManpowerRoleRate.builder()
        .roleId(roleId)
        .categoryId(UUID.randomUUID())
        .gradeId(UUID.randomUUID())
        .unit("Day")
        .rate(new BigDecimal("500.00"))
        .active(true)
        .build();
    UUID manpowerRoleRateId = manpowerRoleRateRepository.save(rate).getId();

    // 11. ResourceAssignment with headcount=2, linked to the manpower rate
    ResourceAssignment assignment = ResourceAssignment.builder()
        .activityId(activityId)
        .projectId(projectId)
        .roleId(roleId)
        .manpowerRoleRateId(manpowerRoleRateId)
        .headcount(2)
        .plannedUnits(20.0)
        .build();
    resourceAssignmentId = resourceAssignmentRepository.save(assignment).getId();
  }

  private HttpHeaders authHeaders() {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(adminToken);
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
  }

  @Test
  @DisplayName("POST /generate-activity-progress: dryRun → generate → idempotent re-run")
  void generate_dryRunThenRealThenIdempotent() {
    String url = "/v1/admin/projects/" + projectId + "/generate-activity-progress";

    // ── Phase 1: dryRun=true ──────────────────────────────────────────────────
    ActivityProgressGenerationRequest dryReq = new ActivityProgressGenerationRequest();
    dryReq.setDryRun(true);
    dryReq.setAutoLockDraft(true);
    dryReq.setDatesPerActivity(2);

    ResponseEntity<ApiResponse> dryResp = restTemplate.exchange(
        url, HttpMethod.POST, new HttpEntity<>(dryReq, authHeaders()), ApiResponse.class);
    assertThat(dryResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    @SuppressWarnings("unchecked")
    Map<String, Object> dryData = (Map<String, Object>) dryResp.getBody().data();
    assertThat(dryData.get("dryRun")).isEqualTo(true);
    assertThat(((Number) dryData.get("activitiesTargeted")).intValue()).isGreaterThanOrEqualTo(1);
    assertThat(((Number) dryData.get("dprsCreated")).intValue()).isEqualTo(0);

    // BOQ qtyExecutedToDate must NOT have changed after dry run
    @SuppressWarnings("unchecked")
    Map<String, Object> boqAfterDry = (Map<String, Object>) restTemplate.exchange(
        "/v1/projects/" + projectId + "/boq/" + boqItemId,
        HttpMethod.GET, new HttpEntity<>(authHeaders()), ApiResponse.class)
        .getBody().data();
    Object qtyAfterDry = boqAfterDry.get("qtyExecutedToDate");
    assertThat(qtyAfterDry == null || ((Number) qtyAfterDry).doubleValue() == 0.0).isTrue();

    // ── Phase 2: dryRun=false ─────────────────────────────────────────────────
    ActivityProgressGenerationRequest realReq = new ActivityProgressGenerationRequest();
    realReq.setDryRun(false);
    realReq.setAutoLockDraft(true);
    realReq.setDatesPerActivity(2);
    realReq.setIncludeResources(true);

    ResponseEntity<ApiResponse> realResp = restTemplate.exchange(
        url, HttpMethod.POST, new HttpEntity<>(realReq, authHeaders()), ApiResponse.class);
    assertThat(realResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    @SuppressWarnings("unchecked")
    Map<String, Object> realData = (Map<String, Object>) realResp.getBody().data();
    assertThat(((Number) realData.get("activitiesGenerated")).intValue()).isGreaterThanOrEqualTo(1);
    assertThat(((Number) realData.get("dprsCreated")).intValue()).isGreaterThanOrEqualTo(1);

    // BOQ qtyExecutedToDate must be in (0, 100] and no OVERRUN status
    @SuppressWarnings("unchecked")
    Map<String, Object> boqAfterReal = (Map<String, Object>) restTemplate.exchange(
        "/v1/projects/" + projectId + "/boq/" + boqItemId,
        HttpMethod.GET, new HttpEntity<>(authHeaders()), ApiResponse.class)
        .getBody().data();
    Number qtyExec = (Number) boqAfterReal.get("qtyExecutedToDate");
    assertThat(qtyExec).isNotNull();
    assertThat(qtyExec.doubleValue()).isGreaterThan(0.0).isLessThanOrEqualTo(100.0);
    Object boqStatus = boqAfterReal.get("status");
    assertThat(boqStatus == null || !"OVERRUN".equals(boqStatus.toString())).isTrue();

    // Activity: percentComplete > 0, editStatus == LOCKED
    @SuppressWarnings("unchecked")
    Map<String, Object> actAfterReal = (Map<String, Object>) restTemplate.exchange(
        "/v1/projects/" + projectId + "/activities/" + activityId,
        HttpMethod.GET, new HttpEntity<>(authHeaders()), ApiResponse.class)
        .getBody().data();
    Number pct = (Number) actAfterReal.get("percentComplete");
    assertThat(pct).isNotNull();
    assertThat(pct.doubleValue()).isGreaterThan(0.0);
    assertThat(actAfterReal.get("editStatus")).isEqualTo("LOCKED");

    // ResourceAssignment.actualUnits > 0 (updated by recomputeActivityResourceActuals)
    ResourceAssignment reloaded = resourceAssignmentRepository.findById(resourceAssignmentId)
        .orElseThrow(() -> new AssertionError("ResourceAssignment not found: " + resourceAssignmentId));
    assertThat(reloaded.getActualUnits()).isNotNull().isGreaterThan(0.0);

    // ── Phase 3: idempotent re-run ────────────────────────────────────────────
    ResponseEntity<ApiResponse> idempResp = restTemplate.exchange(
        url, HttpMethod.POST, new HttpEntity<>(realReq, authHeaders()), ApiResponse.class);
    assertThat(idempResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    @SuppressWarnings("unchecked")
    Map<String, Object> idempData = (Map<String, Object>) idempResp.getBody().data();
    // All DPR slots already covered → dprsCreated == 0 (SKIPPED_EXISTING)
    assertThat(((Number) idempData.get("dprsCreated")).intValue()).isEqualTo(0);

    // qtyExecutedToDate must be unchanged after idempotent re-run
    @SuppressWarnings("unchecked")
    Map<String, Object> boqAfterIdemp = (Map<String, Object>) restTemplate.exchange(
        "/v1/projects/" + projectId + "/boq/" + boqItemId,
        HttpMethod.GET, new HttpEntity<>(authHeaders()), ApiResponse.class)
        .getBody().data();
    Number qtyAfterIdemp = (Number) boqAfterIdemp.get("qtyExecutedToDate");
    assertThat(qtyAfterIdemp).isNotNull();
    assertThat(qtyAfterIdemp.doubleValue()).isEqualTo(qtyExec.doubleValue());
  }
}
