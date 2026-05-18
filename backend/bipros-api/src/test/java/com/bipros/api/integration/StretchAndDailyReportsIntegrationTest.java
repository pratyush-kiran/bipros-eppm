package com.bipros.api.integration;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.*;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Stretch & Daily Reports Integration Tests")
class StretchAndDailyReportsIntegrationTest {

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
        String suffix = "STR" + System.currentTimeMillis();
        RegisterRequest reg = new RegisterRequest(
                "struser" + suffix, "struser" + suffix + "@example.com",
                "testPassword123!", "STR", "User");
        restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);
        LoginRequest login = new LoginRequest("struser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> resp = restTemplate.postForEntity(
                "/v1/auth/login", login, ApiResponse.class);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        token = (String) data.get("accessToken");

        HttpHeaders h = authJsonHeaders();
        CreateEpsNodeRequest epsReq = new CreateEpsNodeRequest(
                "EPS-STR-" + suffix, "EPS Stretch " + suffix, null, null);
        HttpEntity<CreateEpsNodeRequest> epsE = new HttpEntity<>(epsReq, h);
        ResponseEntity<ApiResponse> epsR = restTemplate.exchange("/v1/eps", HttpMethod.POST, epsE, ApiResponse.class);
        Map<String, Object> epsD = (Map<String, Object>) epsR.getBody().data();
        UUID epsId = UUID.fromString((String) epsD.get("id"));

        CreateProjectRequest projReq = new CreateProjectRequest(
                "PRJ-STR-" + suffix, "Project Stretch " + suffix, "desc",
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

    // ===================== STRETCH TESTS =====================

    @Nested
    @DisplayName("Stretch CRUD")
    class StretchTests {

        @Test
        @DisplayName("GET /v1/projects/{id}/stretches - list stretches")
        void listStretches_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/stretches",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("POST /v1/projects/{id}/stretches - create stretch")
        void createStretch_returns201() {
            CreateStretchRequest req = new CreateStretchRequest(
                    "STR-001-" + System.currentTimeMillis(), "Test Stretch",
                    0L, 1000L, null, null, null, null, null, null);
            HttpEntity<CreateStretchRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/stretches",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /v1/stretches/{id} - get stretch")
        void getStretch_returns200() {
            CreateStretchRequest req = new CreateStretchRequest(
                    "STR-G-" + System.currentTimeMillis(), "Get Stretch",
                    0L, 500L, null, null, null, null, null, null);
            HttpEntity<CreateStretchRequest> createE = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/stretches",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/stretches/" + id, HttpMethod.GET, getE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("PUT /v1/stretches/{id} - update stretch")
        void updateStretch_returns200() {
            CreateStretchRequest req = new CreateStretchRequest(
                    "STR-U-" + System.currentTimeMillis(), "Update Stretch",
                    0L, 500L, null, null, null, null, null, null);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<CreateStretchRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/stretches",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            UpdateStretchRequest updateReq = new UpdateStretchRequest(
                    "Updated Stretch Name", 100L, 900L, null, null, null, null, null, null);
            HttpEntity<UpdateStretchRequest> updateE = new HttpEntity<>(updateReq, h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/stretches/" + id, HttpMethod.PUT, updateE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("DELETE /v1/stretches/{id} - delete stretch")
        void deleteStretch_returns204() {
            CreateStretchRequest req = new CreateStretchRequest(
                    "STR-D-" + System.currentTimeMillis(), "Delete Stretch",
                    0L, 500L, null, null, null, null, null, null);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<CreateStretchRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/stretches",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> delE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/stretches/" + id, HttpMethod.DELETE, delE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);
        }
    }

    // ===================== CORRIDOR CODE TESTS =====================

    @Nested
    @DisplayName("Corridor Code")
    class CorridorCodeTests {

        @Test
        @DisplayName("GET /corridor-code - get corridor code")
        void getCorridorCode_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/corridor-code",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("POST /corridor-code - generate corridor code")
        void generateCorridorCode_returns200() {
            CreateCorridorCodeRequest req = new CreateCorridorCodeRequest(
                    projectId, "NH", "Z1", "N01");
            HttpEntity<CreateCorridorCodeRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/corridor-code",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== NEXT DAY PLAN TESTS =====================

    @Nested
    @DisplayName("Next Day Plan")
    class NextDayPlanTests {

        @Test
        @DisplayName("POST /next-day-plan - create")
        void createNextDayPlan_returns201() {
            CreateNextDayPlanRequest req = new CreateNextDayPlanRequest(
                    LocalDate.now(), "Test activity", 0L, 500L,
                    null, null, null, null, null);
            HttpEntity<CreateNextDayPlanRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/next-day-plan",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("POST /next-day-plan/bulk - bulk create")
        void createBulkNextDayPlan_returns201() {
            List<CreateNextDayPlanRequest> items = List.of(
                    new CreateNextDayPlanRequest(LocalDate.now(), "Activity 1",
                            0L, 500L, null, null, null, null, null),
                    new CreateNextDayPlanRequest(LocalDate.now(), "Activity 2",
                            500L, 1000L, null, null, null, null, null));
            HttpEntity<List<CreateNextDayPlanRequest>> e = new HttpEntity<>(items, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/next-day-plan/bulk",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /next-day-plan - list")
        void listNextDayPlan_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/next-day-plan",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("DELETE /next-day-plan/{id} - delete")
        void deleteNextDayPlan_returns204() {
            CreateNextDayPlanRequest req = new CreateNextDayPlanRequest(
                    LocalDate.now(), "To Delete", 0L, 500L,
                    null, null, null, null, null);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<CreateNextDayPlanRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/next-day-plan",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> delE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/next-day-plan/" + id,
                    HttpMethod.DELETE, delE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);
        }
    }

    // ===================== DAILY WEATHER TESTS =====================

    @Nested
    @DisplayName("Daily Weather")
    class DailyWeatherTests {

        @Test
        @DisplayName("POST /weather - create")
        void createWeather_returns201() {
            CreateDailyWeatherRequest req = new CreateDailyWeatherRequest(
                    LocalDate.now(), 35.0, 25.0, 0.0, 10.0, "Sunny", 8.0, null);
            HttpEntity<CreateDailyWeatherRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/weather",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /weather - list")
        void listWeather_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/weather",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== DAILY RESOURCE DEPLOYMENT TESTS =====================

    @Nested
    @DisplayName("Daily Resource Deployment")
    class ResourceDeploymentTests {

        @Test
        @DisplayName("POST /resource-deployment - create")
        void createResourceDeployment_returns201() {
            CreateDailyResourceDeploymentRequest req = new CreateDailyResourceDeploymentRequest(
                    LocalDate.now(), null, "Excavator", null, null,
                    2, 2, 8.0, 0.0, null);
            HttpEntity<CreateDailyResourceDeploymentRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/resource-deployment",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /resource-deployment - list")
        void listResourceDeployment_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/resource-deployment",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== DAILY COST REPORT TESTS =====================

    @Nested
    @DisplayName("Daily Cost Report")
    class DailyCostReportTests {

        @Test
        @DisplayName("GET /daily-cost-report - generate")
        void getDailyCostReport_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/daily-cost-report",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== DAILY PROGRESS REPORT TESTS =====================

    @Nested
    @DisplayName("Daily Progress Report")
    class DprTests {

        @Test
        @DisplayName("POST /dpr - create")
        void createDpr_returns201() {
            CreateDailyProgressReportRequest req = new CreateDailyProgressReportRequest(
                    LocalDate.now(), null, "Supervisor Name", 0L, 500L,
                    null, "Earthwork", null, null, null,
                    "M3", new java.math.BigDecimal("1.0"), null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null);
            HttpEntity<CreateDailyProgressReportRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/dpr",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /dpr - list")
        void listDpr_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/dpr",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== WBS TEMPLATE TESTS =====================

    @Nested
    @DisplayName("WBS Templates")
    class WbsTemplateTests {

        @Test
        @DisplayName("GET /v1/wbs-templates - list")
        void listTemplates_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/wbs-templates", HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("POST /v1/wbs-templates - create")
        void createTemplate_returns201() {
            CreateWbsTemplateRequest req = new CreateWbsTemplateRequest(
                    "TPL-" + System.currentTimeMillis(), "Template Name", null,
                    "Test template", "[]", true);
            HttpEntity<CreateWbsTemplateRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/wbs-templates", HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /v1/wbs-templates/{id} - get by ID")
        void getTemplate_returns200() {
            CreateWbsTemplateRequest req = new CreateWbsTemplateRequest(
                    "TPL-G-" + System.currentTimeMillis(), "Get Template", null,
                    "desc", "[]", true);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<CreateWbsTemplateRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/wbs-templates", HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/wbs-templates/" + id, HttpMethod.GET, getE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
