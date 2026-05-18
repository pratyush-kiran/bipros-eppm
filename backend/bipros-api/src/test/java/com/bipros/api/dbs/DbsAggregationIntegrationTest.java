package com.bipros.api.dbs;

import com.bipros.common.dto.ApiResponse;
import com.bipros.dbs.service.DbsAggregationService;
import com.bipros.project.application.dto.CreateDailyProgressReportRequest;
import com.bipros.project.application.dto.CreateDailyResourceDeploymentRequest;
import com.bipros.project.application.dto.CreateEpsNodeRequest;
import com.bipros.project.application.dto.CreateProjectRequest;
import com.bipros.project.domain.model.DeploymentResourceType;
import com.bipros.resource.application.dto.CreateMaterialConsumptionLogRequest;
import com.bipros.security.application.dto.LoginRequest;
import com.bipros.security.application.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Phase G — DBS aggregation integration tests.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>DPR / DRD / Material Consumption writes trigger {@link DbsAggregationService}
 *       fan-out via {@code DbsRecomputeListener}.</li>
 *   <li>Read endpoints (supervisor / engineer / project, DAY + WEEK + MONTH) return
 *       sensible totals and zero-fill missing days.</li>
 *   <li>Admin {@code /recompute} and {@code /recompute-range} touch the project rollup
 *       row.</li>
 *   <li>Excel + PDF exports return non-empty byte streams with the right MIME type.</li>
 * </ol>
 *
 * <p>Marked {@code @Disabled} pending the Phase G CI wiring — the test infra requires
 * a running Postgres testcontainer plus seeded BOQ rates / a real supervisor user to
 * exercise the aggregation paths end-to-end. Enable once the integration env is up;
 * the stubs are deliberately structured so a later pass can fill in the seed and
 * assert blocks without re-plumbing the request shapes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("DBS Aggregation Integration Tests")
@Disabled("Phase G: enable when integration env (BOQ rates + supervisor seed) is wired")
class DbsAggregationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("bipros_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoSpyBean
    private DbsAggregationService dbsAggregationService;

    private String token;
    private UUID projectId;
    private UUID supervisorUserId; // resolved once seeders give us a real user

    @BeforeEach
    void setUp() {
        String suffix = "DBS" + System.currentTimeMillis();
        RegisterRequest reg = new RegisterRequest(
                "dbsuser" + suffix, "dbsuser" + suffix + "@example.com",
                "testPassword123!", "DBS", "User");
        restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);
        LoginRequest login = new LoginRequest("dbsuser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> resp = restTemplate.postForEntity(
                "/v1/auth/login", login, ApiResponse.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        token = (String) data.get("accessToken");

        HttpHeaders h = authJsonHeaders();
        CreateEpsNodeRequest epsReq = new CreateEpsNodeRequest(
                "EPS-DBS-" + suffix, "EPS DBS " + suffix, null, null);
        HttpEntity<CreateEpsNodeRequest> epsE = new HttpEntity<>(epsReq, h);
        ResponseEntity<ApiResponse> epsR = restTemplate.exchange(
                "/v1/eps", HttpMethod.POST, epsE, ApiResponse.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> epsD = (Map<String, Object>) epsR.getBody().data();
        UUID epsId = UUID.fromString((String) epsD.get("id"));

        CreateProjectRequest projReq = new CreateProjectRequest(
                "PRJ-DBS-" + suffix, "Project DBS " + suffix, "desc",
                epsId, null, LocalDate.now(), LocalDate.now().plusMonths(12),
                5, null, null, null, null, null, null, null, null, null, null);
        HttpEntity<CreateProjectRequest> projE = new HttpEntity<>(projReq, h);
        ResponseEntity<ApiResponse> projR = restTemplate.exchange(
                "/v1/projects", HttpMethod.POST, projE, ApiResponse.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> projD = (Map<String, Object>) projR.getBody().data();
        projectId = UUID.fromString((String) projD.get("id"));

        // supervisorUserId stays null until a real seed plumbs in a supervisor.
        supervisorUserId = null;
    }

    private HttpHeaders authJsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ===================== EVENT-DRIVEN RECOMPUTE =====================

    @Test
    @DisplayName("POST /dpr triggers DbsAggregationService.recomputeSupervisorDay (event fan-out)")
    void createDpr_triggersDbsRecompute() {
        LocalDate today = LocalDate.now();
        CreateDailyProgressReportRequest req = new CreateDailyProgressReportRequest(
                today, supervisorUserId, "Supervisor Name", 0L, 500L,
                null, "Earthwork", null, null, null, "M3",
                new BigDecimal("1.0"), null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
        HttpEntity<CreateDailyProgressReportRequest> e = new HttpEntity<>(req, authJsonHeaders());
        ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                "/v1/projects/" + projectId + "/dpr",
                HttpMethod.POST, e, ApiResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // AFTER_COMMIT listener invokes recomputeSupervisorDay(project, supervisor, today).
        verify(dbsAggregationService, atLeastOnce())
                .recomputeSupervisorDay(eq(projectId), any(), eq(today));
    }

    @Test
    @DisplayName("POST /resource-deployment triggers DBS recompute fan-out")
    void createDeployment_triggersDbsRecompute() {
        LocalDate today = LocalDate.now();
        CreateDailyResourceDeploymentRequest req = new CreateDailyResourceDeploymentRequest(
                today, DeploymentResourceType.EQUIPMENT, "Excavator", null, null,
                2, 2, 8.0, 0.0, null);
        HttpEntity<CreateDailyResourceDeploymentRequest> e = new HttpEntity<>(req, authJsonHeaders());
        ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                "/v1/projects/" + projectId + "/resource-deployment",
                HttpMethod.POST, e, ApiResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        verify(dbsAggregationService, atLeastOnce())
                .recomputeProjectDay(eq(projectId), eq(today));
    }

    @Test
    @DisplayName("POST /material-consumption triggers DBS recompute fan-out")
    void createMaterialConsumption_triggersDbsRecompute() {
        LocalDate today = LocalDate.now();
        CreateMaterialConsumptionLogRequest req = new CreateMaterialConsumptionLogRequest(
                today, null, "Cement OPC 53", "BAG",
                new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("20"),
                null, null, null, null, null, null, null, null, null);
        HttpEntity<CreateMaterialConsumptionLogRequest> e = new HttpEntity<>(req, authJsonHeaders());
        ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                "/v1/projects/" + projectId + "/material-consumption",
                HttpMethod.POST, e, ApiResponse.class);
        assertThat(resp.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);

        verify(dbsAggregationService, atLeastOnce())
                .recomputeProjectDay(eq(projectId), eq(today));
    }

    // ===================== READ ENDPOINTS =====================

    @Test
    @DisplayName("GET /dbs/supervisor/{sid}?periodType=WEEK returns sum of daily rows")
    void getSupervisorPeriod_week_returnsSumOfDailyRows() {
        // TODO(phase-g): pre-seed 3 dbs_daily_supervisor rows in the same ISO week
        // (Mon..Sun) via DbsAggregationService.recomputeSupervisorDay, then assert
        // the WEEK envelope.totals == SUM(individual day amounts).
        LocalDate date = LocalDate.now();
        UUID sid = UUID.randomUUID(); // replace with seeded supervisor
        HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
        ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                "/v1/projects/" + projectId + "/dbs/supervisor/" + sid
                        + "?date=" + date + "&periodType=WEEK",
                HttpMethod.GET, e, ApiResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /dbs/project returns zero-filled response when no row exists")
    void getProjectDay_zeroFillsWhenMissing() {
        LocalDate date = LocalDate.now().plusYears(10); // guaranteed empty
        HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
        ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                "/v1/projects/" + projectId + "/dbs/project?date=" + date,
                HttpMethod.GET, e, ApiResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        // Body should be a zero-filled DbsProjectDayResponse, not an error envelope.
        assertThat(resp.getBody().data()).isNotNull();
    }

    // ===================== ADMIN RECOMPUTE =====================

    @Test
    @DisplayName("POST /dbs/recompute-range loops day-by-day and touches each project row")
    void recomputeRange_loops() {
        LocalDate from = LocalDate.now().minusDays(2);
        LocalDate to = LocalDate.now();
        HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
        ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                "/v1/projects/" + projectId + "/dbs/recompute-range"
                        + "?from=" + from + "&to=" + to,
                HttpMethod.POST, e, ApiResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // recomputeProjectDay is called once per day in [from, to] (inclusive).
        verify(dbsAggregationService, atLeastOnce())
                .recomputeProjectDay(eq(projectId), eq(from));
        verify(dbsAggregationService, atLeastOnce())
                .recomputeProjectDay(eq(projectId), eq(from.plusDays(1)));
        verify(dbsAggregationService, atLeastOnce())
                .recomputeProjectDay(eq(projectId), eq(to));
    }

    // ===================== EXPORTS =====================

    @Test
    @DisplayName("GET /dbs/export.xlsx?level=PM returns spreadsheet bytes")
    void exportXlsx_returnsBytes() {
        LocalDate date = LocalDate.now();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        HttpEntity<Void> e = new HttpEntity<>(h);
        ResponseEntity<byte[]> resp = restTemplate.exchange(
                "/v1/projects/" + projectId + "/dbs/export.xlsx?date=" + date + "&level=PM",
                HttpMethod.GET, e, byte[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType()).isNotNull();
        assertThat(resp.getHeaders().getContentType().toString())
                .contains("spreadsheetml");
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().length).isGreaterThan(100);
    }

    @Test
    @DisplayName("GET /dbs/export.pdf?level=PM returns PDF bytes")
    void exportPdf_returnsBytes() {
        LocalDate date = LocalDate.now();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        HttpEntity<Void> e = new HttpEntity<>(h);
        ResponseEntity<byte[]> resp = restTemplate.exchange(
                "/v1/projects/" + projectId + "/dbs/export.pdf?date=" + date + "&level=PM",
                HttpMethod.GET, e, byte[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().length).isGreaterThan(100);
    }
}
