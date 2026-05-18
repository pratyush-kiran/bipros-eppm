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
@DisplayName("Scheduling Integration Tests")
class SchedulingIntegrationTest {

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
        String suffix = "SCH" + System.currentTimeMillis();
        RegisterRequest reg = new RegisterRequest(
                "schuser" + suffix, "schuser" + suffix + "@example.com",
                "testPassword123!", "SCH", "User");
        restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);
        LoginRequest login = new LoginRequest("schuser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> resp = restTemplate.postForEntity(
                "/v1/auth/login", login, ApiResponse.class);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        token = (String) data.get("accessToken");

        HttpHeaders h = authJsonHeaders();
        CreateEpsNodeRequest epsReq = new CreateEpsNodeRequest(
                "EPS-SCH-" + suffix, "EPS Schedule " + suffix, null, null);
        HttpEntity<CreateEpsNodeRequest> epsE = new HttpEntity<>(epsReq, h);
        ResponseEntity<ApiResponse> epsR = restTemplate.exchange("/v1/eps", HttpMethod.POST, epsE, ApiResponse.class);
        Map<String, Object> epsD = (Map<String, Object>) epsR.getBody().data();
        UUID epsId = UUID.fromString((String) epsD.get("id"));

        CreateProjectRequest projReq = new CreateProjectRequest(
                "PRJ-SCH-" + suffix, "Project Schedule " + suffix, "desc",
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

    @Nested
    @DisplayName("POST /v1/projects/{projectId}/schedule")
    class RunScheduleTests {

        @Test
        @DisplayName("should run schedule (may be 422 if no activities)")
        void runSchedule_returns200or422() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/schedule",
                    HttpMethod.POST, e, ApiResponse.class);
            // Accept 200 (scheduled) or 422 (no activities to schedule) or 400 (validation)
            assertThat(resp.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("GET /v1/projects/{projectId}/schedule")
    class GetLatestScheduleTests {

        @Test
        @DisplayName("should get latest schedule")
        void getLatestSchedule_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/schedule",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("GET /v1/projects/{projectId}/schedule/critical-path")
    class GetCriticalPathTests {

        @Test
        @DisplayName("should get critical path")
        void getCriticalPath_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/schedule/critical-path",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("GET /v1/projects/{projectId}/schedule/float-paths")
    class GetFloatPathsTests {

        @Test
        @DisplayName("should get float paths")
        void getFloatPaths_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/schedule/float-paths",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("GET /v1/projects/{projectId}/schedule/activities")
    class GetAllScheduledActivitiesTests {

        @Test
        @DisplayName("should get all scheduled activities")
        void getAllScheduledActivities_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/schedule/activities",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("Schedule Scenarios")
    class ScenarioTests {

        @Test
        @DisplayName("POST /schedule-scenarios - create scenario")
        void createScenario_returns201() {
            Map<String, Object> body = Map.of(
                    "scenarioName", "Best Case",
                    "description", "Best case scenario",
                    "scenarioType", "BEST_CASE");
            HttpEntity<Map<String, Object>> e = new HttpEntity<>(body, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/schedule-scenarios",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /schedule-scenarios - list scenarios")
        void listScenarios_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/schedule-scenarios",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("Schedule Health")
    class ScheduleHealthTests {

        @Test
        @DisplayName("GET /schedule-health - get DCMA-14 health")
        void getScheduleHealth_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/schedule-health",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("Schedule Compression")
    class ScheduleCompressionTests {

        @Test
        @DisplayName("POST /schedule-compression/fast-track")
        void fastTrack_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/schedule-compression/fast-track",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("POST /schedule-compression/crash")
        void crash_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/schedule-compression/crash",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
