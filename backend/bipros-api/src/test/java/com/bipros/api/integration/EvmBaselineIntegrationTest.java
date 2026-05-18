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
@DisplayName("EVM & Baseline Integration Tests")
class EvmBaselineIntegrationTest {

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
        String suffix = "EB" + System.currentTimeMillis();
        RegisterRequest reg = new RegisterRequest(
                "ebuser" + suffix, "ebuser" + suffix + "@example.com",
                "testPassword123!", "EB", "User");
        restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);
        LoginRequest login = new LoginRequest("ebuser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> resp = restTemplate.postForEntity(
                "/v1/auth/login", login, ApiResponse.class);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        token = (String) data.get("accessToken");

        HttpHeaders h = authJsonHeaders();
        CreateEpsNodeRequest epsReq = new CreateEpsNodeRequest(
                "EPS-EB-" + suffix, "EPS EVM " + suffix, null, null);
        HttpEntity<CreateEpsNodeRequest> epsE = new HttpEntity<>(epsReq, h);
        ResponseEntity<ApiResponse> epsR = restTemplate.exchange("/v1/eps", HttpMethod.POST, epsE, ApiResponse.class);
        Map<String, Object> epsD = (Map<String, Object>) epsR.getBody().data();
        UUID epsId = UUID.fromString((String) epsD.get("id"));

        CreateProjectRequest projReq = new CreateProjectRequest(
                "PRJ-EB-" + suffix, "Project EVM " + suffix, "desc",
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

    // ===================== EVM TESTS =====================

    @Nested
    @DisplayName("EVM")
    class EvmTests {

        @Test
        @DisplayName("POST /evm/calculate - calculate EVM")
        void calculateEvm_returns200or422() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/evm/calculate",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("GET /evm - latest EVM")
        void getLatestEvm_returns200or404() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/evm",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("GET /evm/history - EVM history")
        void getEvmHistory_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/evm/history",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /evm/summary - EVM summary")
        void getEvmSummary_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/evm/summary",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("POST /evm/calculate-wbs - calculate WBS EVM")
        void calculateWbsEvm_returns200or422() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/evm/calculate-wbs",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("GET /evm/wbs-tree - WBS EVM tree")
        void getWbsEvmTree_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/evm/wbs-tree",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== BASELINE TESTS =====================

    @Nested
    @DisplayName("Baselines")
    class BaselineTests {

        @Test
        @DisplayName("POST /baselines - create baseline")
        void createBaseline_returns201() {
            Map<String, Object> body = Map.of(
                    "name", "Baseline-" + System.currentTimeMillis(),
                    "baselineType", "PROJECT",
                    "description", "Test baseline");
            HttpEntity<Map<String, Object>> e = new HttpEntity<>(body, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/baselines",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /baselines - list")
        void listBaselines_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/baselines",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /baselines/{id} - get by ID")
        void getBaseline_returns200() {
            Map<String, Object> body = Map.of(
                    "name", "B-G-" + System.currentTimeMillis(),
                    "baselineType", "PROJECT",
                    "description", "Get test");
            HttpHeaders h = authJsonHeaders();
            HttpEntity<Map<String, Object>> createE = new HttpEntity<>(body, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/baselines",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/baselines/" + id,
                    HttpMethod.GET, getE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("DELETE /baselines/{id} - delete")
        void deleteBaseline_returns204() {
            Map<String, Object> body = Map.of(
                    "name", "B-D-" + System.currentTimeMillis(),
                    "baselineType", "PROJECT",
                    "description", "Delete test");
            HttpHeaders h = authJsonHeaders();
            HttpEntity<Map<String, Object>> createE = new HttpEntity<>(body, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/baselines",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> delE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/baselines/" + id,
                    HttpMethod.DELETE, delE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /baselines/{id}/variance - variance analysis")
        void getVariance_returns200or404() {
            Map<String, Object> body = Map.of(
                    "name", "B-V-" + System.currentTimeMillis(),
                    "baselineType", "PROJECT",
                    "description", "Variance test");
            HttpHeaders h = authJsonHeaders();
            HttpEntity<Map<String, Object>> createE = new HttpEntity<>(body, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/baselines",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/baselines/" + id + "/variance",
                    HttpMethod.GET, getE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("GET /baselines/{id}/schedule-comparison - schedule comparison")
        void getScheduleComparison_returns200or404() {
            Map<String, Object> body = Map.of(
                    "name", "B-SC-" + System.currentTimeMillis(),
                    "baselineType", "PROJECT",
                    "description", "Schedule comparison test");
            HttpHeaders h = authJsonHeaders();
            HttpEntity<Map<String, Object>> createE = new HttpEntity<>(body, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/baselines",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/baselines/" + id + "/schedule-comparison",
                    HttpMethod.GET, getE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("POST /baselines/{id}/activate - set active baseline")
        void activateBaseline_returns200() {
            Map<String, Object> body = Map.of(
                    "name", "B-A-" + System.currentTimeMillis(),
                    "baselineType", "PROJECT",
                    "description", "Activate test");
            HttpHeaders h = authJsonHeaders();
            HttpEntity<Map<String, Object>> createE = new HttpEntity<>(body, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/baselines",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> postE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/baselines/" + id + "/activate",
                    HttpMethod.POST, postE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
