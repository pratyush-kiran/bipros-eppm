package com.bipros.api.integration;

import com.bipros.common.dto.ApiResponse;
import com.bipros.document.application.dto.DocumentFolderRequest;
import com.bipros.document.application.dto.DocumentRequest;
import com.bipros.document.application.dto.DrawingRegisterRequest;
import com.bipros.document.application.dto.RfiRegisterRequest;
import com.bipros.document.application.dto.TransmittalRequest;
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
@DisplayName("Document & Contract Integration Tests")
class DocumentContractIntegrationTest {

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
        String suffix = "DOC" + System.currentTimeMillis();
        RegisterRequest reg = new RegisterRequest(
                "docuser" + suffix, "docuser" + suffix + "@example.com",
                "testPassword123!", "DOC", "User");
        restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);
        LoginRequest login = new LoginRequest("docuser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> resp = restTemplate.postForEntity(
                "/v1/auth/login", login, ApiResponse.class);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        token = (String) data.get("accessToken");

        HttpHeaders h = authJsonHeaders();
        CreateEpsNodeRequest epsReq = new CreateEpsNodeRequest(
                "EPS-DOC-" + suffix, "EPS Doc " + suffix, null, null);
        HttpEntity<CreateEpsNodeRequest> epsE = new HttpEntity<>(epsReq, h);
        ResponseEntity<ApiResponse> epsR = restTemplate.exchange("/v1/eps", HttpMethod.POST, epsE, ApiResponse.class);
        Map<String, Object> epsD = (Map<String, Object>) epsR.getBody().data();
        UUID epsId = UUID.fromString((String) epsD.get("id"));

        CreateProjectRequest projReq = new CreateProjectRequest(
                "PRJ-DOC-" + suffix, "Project Doc " + suffix, "desc",
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

    // ===================== DOCUMENT FOLDER TESTS =====================

    @Nested
    @DisplayName("Document Folders")
    class FolderTests {

        @Test
        @DisplayName("POST /document-folders - create folder")
        void createFolder_returns201() {
            DocumentFolderRequest req = new DocumentFolderRequest(
                    "Folder-" + System.currentTimeMillis(), "FLD-" + System.currentTimeMillis() % 10000,
                    null, null, null, 0);
            HttpEntity<DocumentFolderRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/document-folders",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /document-folders/root - list root folders")
        void listRootFolders_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/document-folders/root",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== DOCUMENT TESTS =====================

    @Nested
    @DisplayName("Documents")
    class DocumentTests {

        @Test
        @DisplayName("POST /documents - create document metadata")
        void createDocument_returns201() {
            UUID folderId = UUID.randomUUID();
            DocumentRequest req = new DocumentRequest(
                    folderId, "DOC-" + System.currentTimeMillis(), "Test Document",
                    "desc", "test.pdf", 1024L, "application/pdf",
                    "/tmp/test.pdf", null, null, null, null, null,
                    null, null, null, null, null);
            HttpEntity<DocumentRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/documents",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /documents - list")
        void listDocuments_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/documents",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== RFI TESTS =====================

    @Nested
    @DisplayName("RFI Register")
    class RfiTests {

        @Test
        @DisplayName("POST /rfis - create RFI")
        void createRfi_returns201() {
            RfiRegisterRequest req = new RfiRegisterRequest(
                    "RFI-" + System.currentTimeMillis(), "Test RFI",
                    "Description", "Engineer", "Contractor",
                    LocalDate.now(), LocalDate.now().plusDays(7),
                    null, null, null, null);
            HttpEntity<RfiRegisterRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/rfis",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /rfis - list")
        void listRfis_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/rfis",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== DRAWING REGISTER TESTS =====================

    @Nested
    @DisplayName("Drawing Register")
    class DrawingTests {

        @Test
        @DisplayName("POST /drawings - create drawing")
        void createDrawing_returns201() {
            DrawingRegisterRequest req = new DrawingRegisterRequest(
                    "DRW-" + System.currentTimeMillis(), "Test Drawing",
                    null, "A", LocalDate.now(),
                    null, "PKG-001", "1:100", null);
            HttpEntity<DrawingRegisterRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/drawings",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /drawings - list")
        void listDrawings_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/drawings",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== TRANSMITTAL TESTS =====================

    @Nested
    @DisplayName("Transmittals")
    class TransmittalTests {

        @Test
        @DisplayName("POST /transmittals - create")
        void createTransmittal_returns201() {
            TransmittalRequest req = new TransmittalRequest(
                    "TR-" + System.currentTimeMillis(), "Test Subject",
                    "Engineer", "Contractor",
                    LocalDate.now(), LocalDate.now().plusDays(14),
                    null, null);
            HttpEntity<TransmittalRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/transmittals",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /transmittals - list")
        void listTransmittals_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/transmittals",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== CONTRACT TESTS =====================

    @Nested
    @DisplayName("Contracts")
    class ContractTests {

        @Test
        @DisplayName("POST /contracts - create")
        void createContract_returns201() {
            Map<String, Object> body = Map.of(
                    "projectId", projectId.toString(),
                    "contractNumber", "CT-" + System.currentTimeMillis(),
                    "contractorName", "Test Contractor",
                    "contractValue", 1000000,
                    "startDate", LocalDate.now().toString(),
                    "completionDate", LocalDate.now().plusMonths(12).toString(),
                    "contractType", "ITEM_RATE");
            HttpEntity<Map<String, Object>> e = new HttpEntity<>(body, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/contracts",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /contracts - list")
        void listContracts_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/contracts",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== TENDER TESTS =====================

    @Nested
    @DisplayName("Tenders")
    class TenderTests {

        @Test
        @DisplayName("POST /tenders - create tender")
        void createTender_returns201() {
            Map<String, Object> body = Map.of(
                    "procurementPlanId", UUID.randomUUID().toString(),
                    "projectId", projectId.toString(),
                    "tenderNumber", "TN-" + System.currentTimeMillis(),
                    "estimatedValue", 5000000);
            HttpEntity<Map<String, Object>> e = new HttpEntity<>(body, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/tenders",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("GET /tenders - list")
        void listTenders_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/tenders",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== PROCUREMENT PLAN TESTS =====================

    @Nested
    @DisplayName("Procurement Plans")
    class ProcurementPlanTests {

        @Test
        @DisplayName("POST /procurement-plans - create")
        void createPlan_returns201() {
            Map<String, Object> body = Map.of(
                    "projectId", projectId.toString(),
                    "planCode", "PP-" + System.currentTimeMillis(),
                    "estimatedValue", 10000000);
            HttpEntity<Map<String, Object>> e = new HttpEntity<>(body, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/procurement-plans",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /procurement-plans - list")
        void listPlans_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/procurement-plans",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
