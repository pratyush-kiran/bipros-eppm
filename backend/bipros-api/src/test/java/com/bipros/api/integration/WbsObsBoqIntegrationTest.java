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
@DisplayName("WBS / OBS / BOQ Integration Tests")
class WbsObsBoqIntegrationTest {

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
        String suffix = "WBS" + System.currentTimeMillis();
        RegisterRequest reg = new RegisterRequest(
                "wbsuser" + suffix, "wbsuser" + suffix + "@example.com",
                "testPassword123!", "WBS", "User");
        restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);
        LoginRequest login = new LoginRequest("wbsuser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> resp = restTemplate.postForEntity(
                "/v1/auth/login", login, ApiResponse.class);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        token = (String) data.get("accessToken");

        // Create EPS + Project
        HttpHeaders h = authJsonHeaders();
        CreateEpsNodeRequest epsReq = new CreateEpsNodeRequest(
                "EPS-WBS-" + suffix, "EPS WBS " + suffix, null, null);
        HttpEntity<CreateEpsNodeRequest> epsE = new HttpEntity<>(epsReq, h);
        ResponseEntity<ApiResponse> epsR = restTemplate.exchange("/v1/eps", HttpMethod.POST, epsE, ApiResponse.class);
        Map<String, Object> epsD = (Map<String, Object>) epsR.getBody().data();
        UUID epsId = UUID.fromString((String) epsD.get("id"));

        CreateProjectRequest projReq = new CreateProjectRequest(
                "PRJ-WBS-" + suffix, "Project WBS " + suffix, "desc",
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

    // ===================== WBS TESTS =====================

    @Nested
    @DisplayName("GET /v1/projects/{projectId}/wbs")
    class GetWbsTreeTests {

        @Test
        @DisplayName("should return WBS tree")
        void getWbsTree_returns200() {
            HttpEntity<Void> entity = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/wbs",
                    HttpMethod.GET, entity, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("POST /v1/projects/{projectId}/wbs")
    class CreateWbsNodeTests {

        @Test
        @DisplayName("should create WBS node")
        void createWbsNode_returns201() {
            String suffix = "N" + System.currentTimeMillis();
            CreateWbsNodeRequest req = new CreateWbsNodeRequest(
                    "WBS-" + suffix, "WBS Node " + suffix, null, projectId, null);

            HttpEntity<CreateWbsNodeRequest> entity = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/wbs",
                    HttpMethod.POST, entity, ApiResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("should create child WBS node")
        void createChildWbsNode_returns201() {
            String suffix = "CH" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();

            CreateWbsNodeRequest rootReq = new CreateWbsNodeRequest(
                    "WBS-R-" + suffix, "WBS Root " + suffix, null, projectId, null);
            HttpEntity<CreateWbsNodeRequest> rootE = new HttpEntity<>(rootReq, h);
            ResponseEntity<ApiResponse> rootR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/wbs", HttpMethod.POST, rootE, ApiResponse.class);
            Map<String, Object> rootD = (Map<String, Object>) rootR.getBody().data();
            UUID rootId = UUID.fromString((String) rootD.get("id"));

            CreateWbsNodeRequest childReq = new CreateWbsNodeRequest(
                    "WBS-CH-" + suffix, "WBS Child " + suffix, rootId, projectId, null);
            HttpEntity<CreateWbsNodeRequest> childE = new HttpEntity<>(childReq, h);
            ResponseEntity<ApiResponse> childR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/wbs", HttpMethod.POST, childE, ApiResponse.class);

            assertThat(childR.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    @Nested
    @DisplayName("GET /v1/projects/{projectId}/wbs/{id}")
    class GetWbsNodeTests {

        @Test
        @DisplayName("should get WBS node by ID")
        void getWbsNode_returns200() {
            String suffix = "G" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateWbsNodeRequest createReq = new CreateWbsNodeRequest(
                    "WBS-G-" + suffix, "WBS Get " + suffix, null, projectId, null);
            HttpEntity<CreateWbsNodeRequest> createE = new HttpEntity<>(createReq, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/wbs", HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/wbs/" + id,
                    HttpMethod.GET, getE, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("PUT /v1/projects/{projectId}/wbs/{id}")
    class UpdateWbsNodeTests {

        @Test
        @DisplayName("should update WBS node")
        void updateWbsNode_returns200() {
            String suffix = "U" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateWbsNodeRequest createReq = new CreateWbsNodeRequest(
                    "WBS-U-" + suffix, "WBS Update " + suffix, null, projectId, null);
            HttpEntity<CreateWbsNodeRequest> createE = new HttpEntity<>(createReq, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/wbs", HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            UpdateEpsNodeRequest updateReq = new UpdateEpsNodeRequest("Updated WBS", null, 0);
            HttpEntity<UpdateEpsNodeRequest> updateE = new HttpEntity<>(updateReq, h);
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/wbs/" + id,
                    HttpMethod.PUT, updateE, ApiResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("DELETE /v1/projects/{projectId}/wbs/{id}")
    class DeleteWbsNodeTests {

        @Test
        @DisplayName("should delete WBS node")
        void deleteWbsNode_returns204() {
            String suffix = "D" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateWbsNodeRequest createReq = new CreateWbsNodeRequest(
                    "WBS-D-" + suffix, "WBS Delete " + suffix, null, projectId, null);
            HttpEntity<CreateWbsNodeRequest> createE = new HttpEntity<>(createReq, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/wbs", HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> delE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/wbs/" + id,
                    HttpMethod.DELETE, delE, ApiResponse.class);
            assertThat(response.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);
        }
    }

    // ===================== OBS TESTS =====================

    @Nested
    @DisplayName("GET /v1/obs")
    class GetObsTreeTests {

        @Test
        @DisplayName("should return OBS tree")
        void getObsTree_returns200() {
            HttpEntity<Void> entity = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/obs", HttpMethod.GET, entity, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("POST /v1/obs")
    class CreateObsNodeTests {

        @Test
        @DisplayName("should create OBS node")
        void createObsNode_returns201() {
            String suffix = "O" + System.currentTimeMillis();
            CreateEpsNodeRequest req = new CreateEpsNodeRequest(
                    "OBS-" + suffix, "OBS Node " + suffix, null, null);

            HttpEntity<CreateEpsNodeRequest> entity = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/obs", HttpMethod.POST, entity, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    @Nested
    @DisplayName("GET /v1/obs/{id}")
    class GetObsNodeTests {

        @Test
        @DisplayName("should get OBS node by ID")
        void getObsNode_returns200() {
            String suffix = "OG" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateEpsNodeRequest req = new CreateEpsNodeRequest(
                    "OBS-G-" + suffix, "OBS Get " + suffix, null, null);
            HttpEntity<CreateEpsNodeRequest> entity = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/obs", HttpMethod.POST, entity, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/obs/" + id, HttpMethod.GET, getE, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("PUT /v1/obs/{id}")
    class UpdateObsNodeTests {

        @Test
        @DisplayName("should update OBS node")
        void updateObsNode_returns200() {
            String suffix = "OU" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateEpsNodeRequest req = new CreateEpsNodeRequest(
                    "OBS-U-" + suffix, "OBS Update " + suffix, null, null);
            HttpEntity<CreateEpsNodeRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/obs", HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            UpdateEpsNodeRequest updateReq = new UpdateEpsNodeRequest("Updated OBS", null, 0);
            HttpEntity<UpdateEpsNodeRequest> updateE = new HttpEntity<>(updateReq, h);
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/obs/" + id, HttpMethod.PUT, updateE, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("DELETE /v1/obs/{id}")
    class DeleteObsNodeTests {

        @Test
        @DisplayName("should delete OBS node")
        void deleteObsNode_returns204() {
            String suffix = "OD" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateEpsNodeRequest req = new CreateEpsNodeRequest(
                    "OBS-D-" + suffix, "OBS Delete " + suffix, null, null);
            HttpEntity<CreateEpsNodeRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/obs", HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> delE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/obs/" + id, HttpMethod.DELETE, delE, ApiResponse.class);
            assertThat(response.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);
        }
    }

    // ===================== BOQ TESTS =====================

    @Nested
    @DisplayName("POST /v1/projects/{projectId}/boq")
    class CreateBoqItemTests {

        @Test
        @DisplayName("should create a BOQ item")
        void createBoqItem_returns201() {
            String suffix = "B" + System.currentTimeMillis();
            CreateBoqItemRequest req = new CreateBoqItemRequest(
                    "BOQ-" + suffix, "BOQ Item " + suffix, "UNITS",
                    null, null, null, null, null, null, null, null);

            HttpEntity<CreateBoqItemRequest> entity = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/boq",
                    HttpMethod.POST, entity, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("should create BOQ items in bulk")
        void createBulkBoqItems_returns201() {
            String suffix = "BLK" + System.currentTimeMillis();
            List<CreateBoqItemRequest> items = List.of(
                    new CreateBoqItemRequest("BOQ-BLK1-" + suffix, "Bulk Item 1 " + suffix, "M3",
                            null, null, null, null, null, null, null, null),
                    new CreateBoqItemRequest("BOQ-BLK2-" + suffix, "Bulk Item 2 " + suffix, "M3",
                            null, null, null, null, null, null, null, null));

            HttpEntity<List<CreateBoqItemRequest>> entity = new HttpEntity<>(items, authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/boq/bulk",
                    HttpMethod.POST, entity, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    @Nested
    @DisplayName("GET /v1/projects/{projectId}/boq")
    class GetBoqSummaryTests {

        @Test
        @DisplayName("should get BOQ summary")
        void getBoqSummary_returns200() {
            HttpEntity<Void> entity = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/boq",
                    HttpMethod.GET, entity, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("GET /v1/projects/{projectId}/boq/{itemId}")
    class GetBoqItemTests {

        @Test
        @DisplayName("should get BOQ item by ID")
        void getBoqItem_returns200() {
            String suffix = "BGI" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateBoqItemRequest req = new CreateBoqItemRequest(
                    "BOQ-G-" + suffix, "BOQ Get " + suffix, "M3",
                    null, null, null, null, null, null, null, null);
            HttpEntity<CreateBoqItemRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/boq",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/boq/" + id,
                    HttpMethod.GET, getE, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("PATCH /v1/projects/{projectId}/boq/{itemId}")
    class UpdateBoqItemTests {

        @Test
        @DisplayName("should update BOQ item")
        void updateBoqItem_returns200() {
            String suffix = "BU" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateBoqItemRequest createReq = new CreateBoqItemRequest(
                    "BOQ-U-" + suffix, "BOQ Update " + suffix, "M3",
                    null, null, null, null, null, null, null, null);
            HttpEntity<CreateBoqItemRequest> createE = new HttpEntity<>(createReq, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/boq",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            UpdateBoqItemRequest updateReq = new UpdateBoqItemRequest(
                    "Updated description", "KG", null, null, null, null, null, null, null, null);
            HttpEntity<UpdateBoqItemRequest> updateE = new HttpEntity<>(updateReq, h);
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/boq/" + id,
                    HttpMethod.PATCH, updateE, ApiResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("DELETE /v1/projects/{projectId}/boq/{itemId}")
    class DeleteBoqItemTests {

        @Test
        @DisplayName("should delete BOQ item")
        void deleteBoqItem_returns204() {
            String suffix = "BD" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateBoqItemRequest createReq = new CreateBoqItemRequest(
                    "BOQ-D-" + suffix, "BOQ Delete " + suffix, "M3",
                    null, null, null, null, null, null, null, null);
            HttpEntity<CreateBoqItemRequest> createE = new HttpEntity<>(createReq, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/boq",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> delE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/boq/" + id,
                    HttpMethod.DELETE, delE, ApiResponse.class);
            assertThat(response.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);
        }
    }
}
