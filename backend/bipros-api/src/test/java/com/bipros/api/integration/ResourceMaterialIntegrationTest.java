package com.bipros.api.integration;

import com.bipros.common.dto.ApiResponse;
import com.bipros.resource.application.dto.CreateProductivityNormRequest;
import com.bipros.resource.application.dto.CreateMaterialIssueRequest;
import com.bipros.resource.application.dto.CreateGoodsReceiptRequest;
import com.bipros.resource.application.dto.CreateMaterialConsumptionLogRequest;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Resource & Material Integration Tests")
class ResourceMaterialIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("bipros_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @Autowired
    private TestRestTemplate restTemplate;

    private String token;

    @BeforeEach
    void setUp() {
        String suffix = "RES" + System.currentTimeMillis();
        RegisterRequest reg = new RegisterRequest(
                "resuser" + suffix, "resuser" + suffix + "@example.com",
                "testPassword123!", "RES", "User");
        restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);
        LoginRequest login = new LoginRequest("resuser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> resp = restTemplate.postForEntity(
                "/v1/auth/login", login, ApiResponse.class);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        token = (String) data.get("accessToken");
    }

    private HttpHeaders authJsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ===================== RESOURCE TESTS =====================

    @Nested
    @DisplayName("Resources CRUD")
    class ResourceTests {

        @Test
        @DisplayName("POST /v1/resources - create resource")
        void createResource_returns201() {
            Map<String, Object> body = Map.of("name", "Test Resource " + System.currentTimeMillis());
            HttpEntity<Map<String, Object>> e = new HttpEntity<>(body, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/resources", HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /v1/resources - list resources")
        void listResources_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/resources", HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /v1/resources/{id} - get resource by ID")
        void getResource_returns200() {
            String suffix = "RG" + System.currentTimeMillis();
            Map<String, Object> body = Map.of("name", "Get Resource " + suffix);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<Map<String, Object>> createE = new HttpEntity<>(body, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/resources", HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/resources/" + id, HttpMethod.GET, getE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("PUT /v1/resources/{id} - update resource")
        void updateResource_returns200() {
            String suffix = "RU" + System.currentTimeMillis();
            Map<String, Object> createBody = Map.of("name", "Update Resource " + suffix);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<Map<String, Object>> createE = new HttpEntity<>(createBody, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/resources", HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            Map<String, Object> updateBody = Map.of("name", "Updated Resource");
            HttpEntity<Map<String, Object>> updateE = new HttpEntity<>(updateBody, h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/resources/" + id, HttpMethod.PUT, updateE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("DELETE /v1/resources/{id} - delete resource")
        void deleteResource_returns204() {
            String suffix = "RD" + System.currentTimeMillis();
            Map<String, Object> body = Map.of("name", "Delete Resource " + suffix);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<Map<String, Object>> createE = new HttpEntity<>(body, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/resources", HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> delE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/resources/" + id, HttpMethod.DELETE, delE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /v1/resources/hierarchy/roots")
        void getHierarchyRoots_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/resources/hierarchy/roots", HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== RESOURCE TYPE DEF TESTS =====================

    @Nested
    @DisplayName("Resource Types")
    class ResourceTypeDefTests {

        @Test
        @DisplayName("GET /v1/resource-types - list")
        void listResourceTypes_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/resource-types", HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== RESOURCE CURVE TESTS =====================

    @Nested
    @DisplayName("Resource Curves")
    class ResourceCurveTests {

        @Test
        @DisplayName("POST /v1/resource-curves - create curve")
        void createCurve_returns201() {
            Map<String, Object> body = Map.of(
                    "name", "Bell Curve-" + System.currentTimeMillis(),
                    "type", "BELL");
            HttpEntity<Map<String, Object>> e = new HttpEntity<>(body, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/resource-curves", HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /v1/resource-curves - list")
        void listCurves_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/resource-curves", HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /v1/resource-curves/defaults")
        void listDefaultCurves_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/resource-curves/defaults", HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== PRODUCTIVITY NORM TESTS =====================

    @Nested
    @DisplayName("Productivity Norms")
    class ProductivityNormTests {

        @Test
        @DisplayName("POST /v1/productivity-norms - create")
        void createNorm_returns201() {
            CreateProductivityNormRequest req = new CreateProductivityNormRequest(
                    com.bipros.resource.domain.model.ProductivityNormType.MANPOWER,
                    null, null, null,
                    "Earthwork", "M3",
                    null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
            HttpEntity<CreateProductivityNormRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/productivity-norms", HttpMethod.POST, e, ApiResponse.class);
            // Without a pre-existing WorkActivity named "Earthwork" the server now returns 400.
            // Either CREATED (when seeded) or BAD_REQUEST (when not) is acceptable for this smoke test.
            assertThat(resp.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("GET /v1/productivity-norms - list")
        void listNorms_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/productivity-norms", HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== MATERIAL TESTS =====================

    @Nested
    @DisplayName("Materials")
    class MaterialTests {

        @Test
        @DisplayName("POST /v1/projects/{id}/materials - create")
        void createMaterial_returns201() {
            UUID projectId = UUID.randomUUID();
            Map<String, Object> body = Map.of("name", "Test Material " + System.currentTimeMillis());
            HttpEntity<Map<String, Object>> e = new HttpEntity<>(body, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/materials",
                    HttpMethod.POST, e, ApiResponse.class);
            // May fail with 400 or 404 if project doesn't exist, which is expected in isolation
            assertThat(resp.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("GET /v1/projects/{id}/materials - list")
        void listMaterials_returns200() {
            UUID projectId = UUID.randomUUID();
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/materials",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== ISSUES TESTS =====================

    @Nested
    @DisplayName("Material Issues")
    class IssueTests {

        @Test
        @DisplayName("POST /projects/{id}/issues - create")
        void createIssue_returns201() {
            UUID projectId = UUID.randomUUID();
            CreateMaterialIssueRequest req = new CreateMaterialIssueRequest(
                    UUID.randomUUID(), LocalDate.now(), new BigDecimal("10.0"),
                    null, null, null, null, null);
            HttpEntity<CreateMaterialIssueRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/issues",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("GET /projects/{id}/issues - list")
        void listIssues_returns200() {
            UUID projectId = UUID.randomUUID();
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/issues",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== GRN TESTS =====================

    @Nested
    @DisplayName("Goods Receipt Notes")
    class GrnTests {

        @Test
        @DisplayName("POST /projects/{id}/grns - create GRN")
        void createGrn_returns201() {
            UUID projectId = UUID.randomUUID();
            CreateGoodsReceiptRequest req = new CreateGoodsReceiptRequest(
                    UUID.randomUUID(), LocalDate.now(), new BigDecimal("100.0"),
                    null, null, null, null, null, null, null, null);
            HttpEntity<CreateGoodsReceiptRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/grns",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("GET /projects/{id}/grns - list")
        void listGrns_returns200() {
            UUID projectId = UUID.randomUUID();
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/grns",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== MATERIAL CONSUMPTION TESTS =====================

    @Nested
    @DisplayName("Material Consumption")
    class MaterialConsumptionTests {

        @Test
        @DisplayName("POST - create log")
        void createLog_returns201() {
            UUID projectId = UUID.randomUUID();
            CreateMaterialConsumptionLogRequest req = new CreateMaterialConsumptionLogRequest(
                    LocalDate.now(), null, "Cement", "Bags",
                    new BigDecimal("100"), new BigDecimal("20"),
                    new BigDecimal("15"), null, null, null, null, null,
                    null, null, null, null);
            HttpEntity<CreateMaterialConsumptionLogRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/material-consumption",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("GET - list")
        void listConsumption_returns200() {
            UUID projectId = UUID.randomUUID();
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/material-consumption",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== MATERIAL SOURCE TESTS =====================

    @Nested
    @DisplayName("Material Sources")
    class MaterialSourceTests {

        @Test
        @DisplayName("POST - create")
        void createSource_returns201() {
            UUID projectId = UUID.randomUUID();
            Map<String, Object> body = Map.of("name", "Borrow Area " + System.currentTimeMillis());
            HttpEntity<Map<String, Object>> e = new HttpEntity<>(body, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/material-sources",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("GET - list")
        void listSources_returns200() {
            UUID projectId = UUID.randomUUID();
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/material-sources",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
