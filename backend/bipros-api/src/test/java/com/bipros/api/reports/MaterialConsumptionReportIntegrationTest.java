package com.bipros.api.reports;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateEpsNodeRequest;
import com.bipros.project.application.dto.CreateProjectRequest;
import com.bipros.resource.application.dto.CreateMaterialConsumptionLogRequest;
import com.bipros.security.application.dto.LoginRequest;
import com.bipros.security.application.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/**
 * Phase G — Material Consumption Report integration tests.
 *
 * <p>Hits {@code /v1/projects/{id}/reports/material-consumption} for the JSON
 * report payload and {@code .../export.xlsx} for the spreadsheet variant. Seeds
 * a small handful of {@code MaterialConsumptionLog} rows so the report has
 * something to roll up, then asserts filter / group-by / alert behaviour.
 *
 * <p>Disabled until the Phase G integration env is wired (Postgres testcontainer,
 * BOQ rate seed for the planned-vs-actual delta computation, and a seeded
 * supervisor user so the supervisor-filter assertion can use a real FK).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Material Consumption Report Integration Tests")
@Disabled("Phase G: enable when integration env (BOQ rates + supervisor seed) is wired")
class MaterialConsumptionReportIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("bipros_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @Autowired
    private TestRestTemplate restTemplate;

    private String token;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        String suffix = "MCR" + System.currentTimeMillis();
        RegisterRequest reg = new RegisterRequest(
                "mcruser" + suffix, "mcruser" + suffix + "@example.com",
                "testPassword123!", "MCR", "User");
        restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);
        LoginRequest login = new LoginRequest("mcruser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> resp = restTemplate.postForEntity(
                "/v1/auth/login", login, ApiResponse.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        token = (String) data.get("accessToken");

        HttpHeaders h = authJsonHeaders();
        CreateEpsNodeRequest epsReq = new CreateEpsNodeRequest(
                "EPS-MCR-" + suffix, "EPS MCR " + suffix, null, null);
        HttpEntity<CreateEpsNodeRequest> epsE = new HttpEntity<>(epsReq, h);
        ResponseEntity<ApiResponse> epsR = restTemplate.exchange(
                "/v1/eps", HttpMethod.POST, epsE, ApiResponse.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> epsD = (Map<String, Object>) epsR.getBody().data();
        UUID epsId = UUID.fromString((String) epsD.get("id"));

        CreateProjectRequest projReq = new CreateProjectRequest(
                "PRJ-MCR-" + suffix, "Project MCR " + suffix, "desc",
                epsId, null, LocalDate.now(), LocalDate.now().plusMonths(12),
                5, null, null, null, null, null, null, null, null, null, null);
        HttpEntity<CreateProjectRequest> projE = new HttpEntity<>(projReq, h);
        ResponseEntity<ApiResponse> projR = restTemplate.exchange(
                "/v1/projects", HttpMethod.POST, projE, ApiResponse.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> projD = (Map<String, Object>) projR.getBody().data();
        projectId = UUID.fromString((String) projD.get("id"));
    }

    private HttpHeaders authJsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /** Helper: post a single material consumption log row. */
    private void seedLog(LocalDate date, String material, BigDecimal consumed) {
        CreateMaterialConsumptionLogRequest req = new CreateMaterialConsumptionLogRequest(
                date, null, material, "BAG",
                new BigDecimal("100"), new BigDecimal("50"), consumed,
                null, null, null, null, null, null, null, null, null);
        HttpEntity<CreateMaterialConsumptionLogRequest> e = new HttpEntity<>(req, authJsonHeaders());
        restTemplate.exchange(
                "/v1/projects/" + projectId + "/material-consumption",
                HttpMethod.POST, e, ApiResponse.class);
    }

    @Test
    @DisplayName("GET /reports/material-consumption with no filter returns all project rows")
    void generate_emptyFilter_returnsAllRowsInProject() {
        LocalDate today = LocalDate.now();
        seedLog(today, "Cement OPC 53", new BigDecimal("10"));
        seedLog(today, "Steel TMT", new BigDecimal("5"));

        HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
        ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                "/v1/projects/" + projectId + "/reports/material-consumption",
                HttpMethod.GET, e, ApiResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().data()).isNotNull();
    }

    @Test
    @DisplayName("GET .../?supervisorUserId=X filters rows to that supervisor only")
    void generate_supervisorFilter_filtersRows() {
        // TODO(phase-g): seed two rows with different supervisorUserIds; assert
        // the filter shrinks the result set to just the one we asked for.
        UUID supervisorId = UUID.randomUUID();
        HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
        ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                "/v1/projects/" + projectId + "/reports/material-consumption"
                        + "?supervisorUserId=" + supervisorId,
                HttpMethod.GET, e, ApiResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET .../?groupBy=ACTIVITY collapses rows by activity")
    void generate_groupByActivity_collapsesRows() {
        LocalDate today = LocalDate.now();
        seedLog(today, "Cement OPC 53", new BigDecimal("10"));
        seedLog(today, "Cement OPC 53", new BigDecimal("12")); // same activity, second log
        seedLog(today, "Steel TMT", new BigDecimal("5"));

        HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
        ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                "/v1/projects/" + projectId + "/reports/material-consumption?groupBy=ACTIVITY",
                HttpMethod.GET, e, ApiResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // TODO(phase-g): assert that the response.rows().size() ≤ number of distinct activities.
    }

    @Test
    @DisplayName("Excess consumption (consumed > 1.10 × planned) emits an alert flag")
    void generate_excessConsumption_emitsAlert() {
        // TODO(phase-g): seed a BOQ planned qty of 10 and consume 12+ for the same
        // activity / material so MaterialConsumptionAlertEvaluator flags it. Then
        // assert the row carries an "excess consumption" alert.
        LocalDate today = LocalDate.now();
        seedLog(today, "Cement OPC 53", new BigDecimal("999"));

        HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
        ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                "/v1/projects/" + projectId + "/reports/material-consumption",
                HttpMethod.GET, e, ApiResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /reports/material-consumption/export.xlsx returns spreadsheet bytes")
    void export_excel_returnsBytes() {
        seedLog(LocalDate.now(), "Cement OPC 53", new BigDecimal("10"));

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        HttpEntity<Void> e = new HttpEntity<>(h);
        ResponseEntity<byte[]> resp = restTemplate.exchange(
                "/v1/projects/" + projectId + "/reports/material-consumption/export.xlsx",
                HttpMethod.GET, e, byte[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType()).isNotNull();
        assertThat(resp.getHeaders().getContentType().toString())
                .contains("spreadsheetml");
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().length).isGreaterThan(100);
    }
}
