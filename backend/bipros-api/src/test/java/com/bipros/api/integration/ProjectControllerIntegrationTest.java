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
import org.springframework.web.util.UriComponentsBuilder;
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
@DisplayName("Project Controller Integration Tests")
class ProjectControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("bipros_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @Autowired
    private TestRestTemplate restTemplate;

    private String token;
    private UUID epsNodeId;

    @BeforeEach
    void setUp() {
        String suffix = "PRJ" + System.currentTimeMillis();
        RegisterRequest reg = new RegisterRequest(
                "prjuser" + suffix, "prjuser" + suffix + "@example.com",
                "testPassword123!", "PRJ", "User");
        restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);
        LoginRequest login = new LoginRequest("prjuser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> resp = restTemplate.postForEntity(
                "/v1/auth/login", login, ApiResponse.class);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        token = (String) data.get("accessToken");

        // Create EPS node
        HttpHeaders headers = authJsonHeaders();
        CreateEpsNodeRequest epsReq = new CreateEpsNodeRequest(
                "EPS-PRJ-" + suffix, "EPS Project " + suffix, null, null);
        HttpEntity<CreateEpsNodeRequest> epsEntity = new HttpEntity<>(epsReq, headers);
        ResponseEntity<ApiResponse> epsResp = restTemplate.exchange(
                "/v1/eps", HttpMethod.POST, epsEntity, ApiResponse.class);
        Map<String, Object> epsData = (Map<String, Object>) epsResp.getBody().data();
        epsNodeId = UUID.fromString((String) epsData.get("id"));
    }

    private HttpHeaders authJsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Nested
    @DisplayName("GET /v1/projects")
    class ListProjectsTests {

        @Test
        @DisplayName("should return paginated project list")
        void listProjects_returns200() {
            HttpEntity<Void> entity = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects", HttpMethod.GET, entity, ApiResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
        }

        @Test
        @DisplayName("should return 401 without auth")
        void listProjects_withoutAuth_returns401() {
            ResponseEntity<ApiResponse> response = restTemplate.getForEntity(
                    "/v1/projects", ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("POST /v1/projects")
    class CreateProjectTests {

        @Test
        @DisplayName("should create a project")
        void createProject_returns201() {
            String suffix = "CP" + System.currentTimeMillis();
            CreateProjectRequest req = new CreateProjectRequest(
                    "PROJ-" + suffix, "Project " + suffix, "Test project",
                    epsNodeId, null, LocalDate.now(), LocalDate.now().plusMonths(12),
                    5, null, null, null, null, null, null, null, null, null,
                    null);

            HttpEntity<CreateProjectRequest> entity = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects", HttpMethod.POST, entity, ApiResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().error()).isNull();
        }

        @Test
        @DisplayName("should reject project with blank code")
        void createProjectBlankCode_returns400() {
            CreateProjectRequest req = new CreateProjectRequest(
                    "", "No Code", "desc", epsNodeId, null,
                    LocalDate.now(), LocalDate.now().plusMonths(12), 5,
                    null, null, null, null, null, null, null, null, null,
                    null);

            HttpEntity<CreateProjectRequest> entity = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects", HttpMethod.POST, entity, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should reject project with blank name")
        void createProjectBlankName_returns400() {
            CreateProjectRequest req = new CreateProjectRequest(
                    "PROJ-BN", "", "desc", epsNodeId, null,
                    LocalDate.now(), LocalDate.now().plusMonths(12), 5,
                    null, null, null, null, null, null, null, null, null,
                    null);

            HttpEntity<CreateProjectRequest> entity = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects", HttpMethod.POST, entity, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should reject project with null EPS node ID")
        void createProjectNullEps_returns400() {
            CreateProjectRequest req = new CreateProjectRequest(
                    "PROJ-NE", "No EPS", "desc", null, null,
                    LocalDate.now(), LocalDate.now().plusMonths(12), 5,
                    null, null, null, null, null, null, null, null, null,
                    null);

            HttpEntity<CreateProjectRequest> entity = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects", HttpMethod.POST, entity, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should reject project with finish before start")
        void createProjectFinishBeforeStart_returns422() {
            CreateProjectRequest req = new CreateProjectRequest(
                    "PROJ-FBS-" + System.currentTimeMillis(), "FBS Test", "desc",
                    epsNodeId, null,
                    LocalDate.now().plusMonths(12), LocalDate.now(),
                    5, null, null, null, null, null, null, null, null, null,
                    null);

            HttpEntity<CreateProjectRequest> entity = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects", HttpMethod.POST, entity, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Nested
    @DisplayName("GET /v1/projects/{id}")
    class GetProjectTests {

        @Test
        @DisplayName("should get project by ID")
        void getProject_returns200() {
            HttpHeaders headers = authJsonHeaders();
            String suffix = "GP" + System.currentTimeMillis();
            CreateProjectRequest req = new CreateProjectRequest(
                    "PROJ-GP-" + suffix, "Get Project " + suffix, "desc",
                    epsNodeId, null, LocalDate.now(), LocalDate.now().plusMonths(12),
                    5, null, null, null, null, null, null, null, null, null,
                    null);
            HttpEntity<CreateProjectRequest> createEntity = new HttpEntity<>(req, headers);
            ResponseEntity<ApiResponse> createResp = restTemplate.exchange(
                    "/v1/projects", HttpMethod.POST, createEntity, ApiResponse.class);
            Map<String, Object> data = (Map<String, Object>) createResp.getBody().data();
            String id = (String) data.get("id");

            HttpEntity<Void> getEntity = new HttpEntity<>(headers);
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + id, HttpMethod.GET, getEntity, ApiResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("should return 404 for non-existent project")
        void getNonExistentProject_returns404() {
            HttpEntity<Void> entity = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/00000000-0000-0000-0000-000000000000",
                    HttpMethod.GET, entity, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("PUT /v1/projects/{id}")
    class UpdateProjectTests {

        @Test
        @DisplayName("should update project")
        void updateProject_returns200() {
            String suffix = "UP" + System.currentTimeMillis();
            HttpHeaders headers = authJsonHeaders();
            CreateProjectRequest createReq = new CreateProjectRequest(
                    "PROJ-UP-" + suffix, "Update Project " + suffix, "desc",
                    epsNodeId, null, LocalDate.now(), LocalDate.now().plusMonths(12),
                    5, null, null, null, null, null, null, null, null, null,
                    null);
            HttpEntity<CreateProjectRequest> createEntity = new HttpEntity<>(createReq, headers);
            ResponseEntity<ApiResponse> createResp = restTemplate.exchange(
                    "/v1/projects", HttpMethod.POST, createEntity, ApiResponse.class);
            Map<String, Object> data = (Map<String, Object>) createResp.getBody().data();
            String id = (String) data.get("id");

            Map<String, Object> updateBody = Map.of("name", "Updated Name", "description", "Updated desc");
            HttpHeaders putHeaders = authJsonHeaders();
            putHeaders.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> updateEntity = new HttpEntity<>(updateBody, putHeaders);
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + id, HttpMethod.PUT, updateEntity, ApiResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("DELETE /v1/projects/{id}")
    class DeleteProjectTests {

        @Test
        @DisplayName("should delete project")
        void deleteProject_returns204() {
            String suffix = "DP" + System.currentTimeMillis();
            HttpHeaders headers = authJsonHeaders();
            CreateProjectRequest createReq = new CreateProjectRequest(
                    "PROJ-DP-" + suffix, "Delete Project " + suffix, "desc",
                    epsNodeId, null, LocalDate.now(), LocalDate.now().plusMonths(12),
                    5, null, null, null, null, null, null, null, null, null,
                    null);
            HttpEntity<CreateProjectRequest> createEntity = new HttpEntity<>(createReq, headers);
            ResponseEntity<ApiResponse> createResp = restTemplate.exchange(
                    "/v1/projects", HttpMethod.POST, createEntity, ApiResponse.class);
            Map<String, Object> data = (Map<String, Object>) createResp.getBody().data();
            String id = (String) data.get("id");

            HttpEntity<Void> deleteEntity = new HttpEntity<>(headers);
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + id, HttpMethod.DELETE, deleteEntity, ApiResponse.class);

            assertThat(response.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("GET /v1/projects/by-eps/{epsNodeId}")
    class GetProjectsByEpsTests {

        @Test
        @DisplayName("should list projects by EPS node")
        void getProjectsByEps_returns200() {
            HttpEntity<Void> entity = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/by-eps/" + epsNodeId,
                    HttpMethod.GET, entity, ApiResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("should return 200 for EPS with no projects (empty list)")
        void getProjectsByEmptyEps_returns200() {
            HttpEntity<Void> entity = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/by-eps/00000000-0000-0000-0000-000000000000",
                    HttpMethod.GET, entity, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
