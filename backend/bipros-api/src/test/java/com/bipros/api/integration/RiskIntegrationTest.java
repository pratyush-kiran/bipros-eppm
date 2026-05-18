package com.bipros.api.integration;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateEpsNodeRequest;
import com.bipros.project.application.dto.CreateProjectRequest;
import com.bipros.security.application.dto.LoginRequest;
import com.bipros.security.application.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Risk Integration Tests")
class RiskIntegrationTest {

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
        String suffix = "RISK" + System.currentTimeMillis();
        RegisterRequest reg = new RegisterRequest(
                "riskuser" + suffix, "riskuser" + suffix + "@example.com",
                "testPassword123!", "RISK", "User");
        restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);
        LoginRequest login = new LoginRequest("riskuser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> resp = restTemplate.postForEntity(
                "/v1/auth/login", login, ApiResponse.class);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        token = (String) data.get("accessToken");

        HttpHeaders h = authJsonHeaders();
        CreateEpsNodeRequest epsReq = new CreateEpsNodeRequest(
                "EPS-RISK-" + suffix, "EPS Risk " + suffix, null, null);
        HttpEntity<CreateEpsNodeRequest> epsE = new HttpEntity<>(epsReq, h);
        ResponseEntity<ApiResponse> epsR = restTemplate.exchange("/v1/eps", HttpMethod.POST, epsE, ApiResponse.class);
        Map<String, Object> epsD = (Map<String, Object>) epsR.getBody().data();
        UUID epsId = UUID.fromString((String) epsD.get("id"));

        CreateProjectRequest projReq = new CreateProjectRequest(
                "PRJ-RISK-" + suffix, "Project Risk " + suffix, "desc",
                epsId, null, LocalDate.now(), LocalDate.now().plusMonths(12),
                5, null, null, null, null, null, null, null, null, null,
                null);
        HttpEntity<CreateProjectRequest> projE = new HttpEntity<>(projReq, h);
        ResponseEntity<ApiResponse> projR = restTemplate.exchange("/v1/projects", HttpMethod.POST, projE, ApiResponse.class);
        Map<String, Object> projD = (Map<String, Object>) projR.getBody().data();
        projectId = UUID.fromString((String) projD.get("id"));
    }

    private HttpHeaders authJsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ===================== RISK TESTS =====================

    @Nested
    @DisplayName("Risks")
    class RiskTests {

        @Test
        @DisplayName("POST /risks - create risk")
        void createRisk_returns201() {
            Map<String, Object> body = Map.of(
                    "code", "RISK-" + System.currentTimeMillis(),
                    "title", "Test Risk",
                    "description", "Test risk description",
                    "category", "TECHNICAL",
                    "probability", "MEDIUM",
                    "impact", "HIGH",
                    "status", "OPEN");
            HttpEntity<Map<String, Object>> e = new HttpEntity<>(body, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risks",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /risks - list by project")
        void listRisks_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risks",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /risks/{riskId} - get risk")
        void getRisk_returns200() {
            Map<String, Object> body = Map.of(
                    "code", "RISK-G-" + System.currentTimeMillis(),
                    "title", "Get Risk",
                    "description", "Test", "category", "TECHNICAL",
                    "probability", "LOW", "impact", "LOW", "status", "OPEN");
            HttpHeaders h = authJsonHeaders();
            HttpEntity<Map<String, Object>> createE = new HttpEntity<>(body, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risks",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risks/" + id,
                    HttpMethod.GET, getE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("PUT /risks/{riskId} - update risk")
        void updateRisk_returns200() {
            Map<String, Object> body = Map.of(
                    "code", "RISK-U-" + System.currentTimeMillis(),
                    "title", "Update Risk", "description", "Test",
                    "category", "TECHNICAL", "probability", "LOW",
                    "impact", "LOW", "status", "OPEN");
            HttpHeaders h = authJsonHeaders();
            HttpEntity<Map<String, Object>> createE = new HttpEntity<>(body, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risks",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            Map<String, Object> updateBody = Map.of(
                    "title", "Updated Risk",
                    "status", "CLOSED");
            HttpEntity<Map<String, Object>> updateE = new HttpEntity<>(updateBody, h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risks/" + id,
                    HttpMethod.PUT, updateE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("DELETE /risks/{riskId} - delete risk")
        void deleteRisk_returns204() {
            Map<String, Object> body = Map.of(
                    "code", "RISK-D-" + System.currentTimeMillis(),
                    "title", "Delete Risk", "description", "Test",
                    "category", "TECHNICAL", "probability", "LOW",
                    "impact", "LOW", "status", "OPEN");
            HttpHeaders h = authJsonHeaders();
            HttpEntity<Map<String, Object>> createE = new HttpEntity<>(body, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risks",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> delE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risks/" + id,
                    HttpMethod.DELETE, delE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /risks/summary - risk summary")
        void getRiskSummary_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risks/summary",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /risks/matrix - risk matrix")
        void getRiskMatrix_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risks/matrix",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /risks/exposure - risk exposure")
        void getRiskExposure_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risks/exposure",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("POST /risks/{riskId}/responses - add response")
        void addRiskResponse_returns201() {
            Map<String, Object> riskBody = Map.of(
                    "code", "RISK-RP-" + System.currentTimeMillis(),
                    "title", "Response Risk", "description", "Test",
                    "category", "TECHNICAL", "probability", "LOW",
                    "impact", "LOW", "status", "OPEN");
            HttpHeaders h = authJsonHeaders();
            HttpEntity<Map<String, Object>> createE = new HttpEntity<>(riskBody, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risks",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String riskId = (String) d.get("id");

            Map<String, Object> respBody = Map.of(
                    "responseType", "MITIGATE",
                    "description", "Mitigation action");
            HttpEntity<Map<String, Object>> respE = new HttpEntity<>(respBody, h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risks/" + riskId + "/responses",
                    HttpMethod.POST, respE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    // ===================== RISK TEMPLATE TESTS =====================

    @Nested
    @DisplayName("Risk Templates")
    class RiskTemplateTests {

        @Test
        @DisplayName("GET /v1/risk-templates - list")
        void listTemplates_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/risk-templates", HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("POST /v1/risk-templates - create")
        void createTemplate_returns201() {
            Map<String, Object> body = Map.of(
                    "code", "TPL-" + System.currentTimeMillis(),
                    "title", "Test Template",
                    "category", "TECHNICAL",
                    "description", "Template description",
                    "industry", "ROAD");
            HttpEntity<Map<String, Object>> e = new HttpEntity<>(body, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/risk-templates", HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    // ===================== RISK TRIGGER TESTS =====================

    @Nested
    @DisplayName("Risk Triggers")
    class RiskTriggerTests {

        @Test
        @DisplayName("GET /risk-triggers - list")
        void listTriggers_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risk-triggers",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("POST /risk-triggers/evaluate - evaluate triggers")
        void evaluateTriggers_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risk-triggers/evaluate",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /risk-triggers/triggered - triggered risks")
        void getTriggeredRisks_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/risk-triggers/triggered",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== MONTE CARLO TESTS =====================

    @Nested
    @DisplayName("Monte Carlo")
    class MonteCarloTests {

        @Test
        @DisplayName("POST /monte-carlo/run - run simulation")
        void runSimulation_returns200or422() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/monte-carlo/run",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("GET /monte-carlo - list simulations")
        void listSimulations_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/monte-carlo",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /monte-carlo/latest - latest simulation")
        void getLatestSimulation_returns200or404() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/monte-carlo/latest",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NOT_FOUND);
        }
    }
}
