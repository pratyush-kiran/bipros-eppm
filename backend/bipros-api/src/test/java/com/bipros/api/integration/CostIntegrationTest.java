package com.bipros.api.integration;

import com.bipros.common.dto.ApiResponse;
import com.bipros.cost.application.dto.CreateCostAccountRequest;
import com.bipros.cost.application.dto.CreateFundingSourceRequest;
import com.bipros.cost.application.dto.CreateFinancialPeriodRequest;
import com.bipros.cost.application.dto.CreateRaBillRequest;
import com.bipros.cost.application.dto.CreateCashFlowForecastRequest;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Cost Integration Tests")
class CostIntegrationTest {

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
        String suffix = "CST" + System.currentTimeMillis();
        RegisterRequest reg = new RegisterRequest(
                "cstuser" + suffix, "cstuser" + suffix + "@example.com",
                "testPassword123!", "CST", "User");
        restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);
        LoginRequest login = new LoginRequest("cstuser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> resp = restTemplate.postForEntity(
                "/v1/auth/login", login, ApiResponse.class);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        token = (String) data.get("accessToken");

        HttpHeaders h = authJsonHeaders();
        CreateEpsNodeRequest epsReq = new CreateEpsNodeRequest(
                "EPS-CST-" + suffix, "EPS Cost " + suffix, null, null);
        HttpEntity<CreateEpsNodeRequest> epsE = new HttpEntity<>(epsReq, h);
        ResponseEntity<ApiResponse> epsR = restTemplate.exchange("/v1/eps", HttpMethod.POST, epsE, ApiResponse.class);
        Map<String, Object> epsD = (Map<String, Object>) epsR.getBody().data();
        UUID epsId = UUID.fromString((String) epsD.get("id"));

        CreateProjectRequest projReq = new CreateProjectRequest(
                "PRJ-CST-" + suffix, "Project Cost " + suffix, "desc",
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

    // ===================== COST ACCOUNTS =====================

    @Nested
    @DisplayName("Cost Accounts")
    class CostAccountTests {

        @Test
        @DisplayName("POST /v1/cost-accounts - create")
        void createCostAccount_returns201() {
            CreateCostAccountRequest req = new CreateCostAccountRequest(
                    "CA-" + System.currentTimeMillis(), "Test Cost Account", "desc", null, 0);
            HttpEntity<CreateCostAccountRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/cost-accounts", HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /v1/cost-accounts - tree")
        void getCostAccountTree_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/cost-accounts", HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /v1/cost-accounts/{id} - get by ID")
        void getCostAccount_returns200() {
            String suffix = "CA" + System.currentTimeMillis();
            CreateCostAccountRequest req = new CreateCostAccountRequest(
                    "CA-G-" + suffix, "Get CA " + suffix, "desc", null, 0);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<CreateCostAccountRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/cost-accounts", HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/cost-accounts/" + id, HttpMethod.GET, getE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("PUT /v1/cost-accounts/{id} - update")
        void updateCostAccount_returns200() {
            String suffix = "CU" + System.currentTimeMillis();
            CreateCostAccountRequest req = new CreateCostAccountRequest(
                    "CA-U-" + suffix, "Update CA " + suffix, "desc", null, 0);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<CreateCostAccountRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/cost-accounts", HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            CreateCostAccountRequest updateReq = new CreateCostAccountRequest(
                    "CA-U-" + suffix, "Updated CA", "updated desc", null, 1);
            HttpEntity<CreateCostAccountRequest> updateE = new HttpEntity<>(updateReq, h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/cost-accounts/" + id, HttpMethod.PUT, updateE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("DELETE /v1/cost-accounts/{id} - delete")
        void deleteCostAccount_returns204() {
            String suffix = "CD" + System.currentTimeMillis();
            CreateCostAccountRequest req = new CreateCostAccountRequest(
                    "CA-D-" + suffix, "Delete CA " + suffix, "desc", null, 0);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<CreateCostAccountRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/cost-accounts", HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> delE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/cost-accounts/" + id, HttpMethod.DELETE, delE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);
        }
    }

    // ===================== EXPENSES =====================

    @Nested
    @DisplayName("Activity Expenses")
    class ExpenseTests {

        @Test
        @DisplayName("POST /projects/{id}/expenses - create")
        void createExpense_returns201() {
            Map<String, Object> body = Map.of(
                    "projectId", projectId.toString(),
                    "name", "Test Expense",
                    "budgetedCost", 1000,
                    "expenseDate", LocalDate.now().toString());
            HttpEntity<Map<String, Object>> e = new HttpEntity<>(body, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/expenses",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /projects/{id}/expenses - list")
        void listExpenses_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/expenses",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("PUT /projects/{id}/expenses/{id} - update")
        void updateExpense_returns200() {
            Map<String, Object> createBody = Map.of(
                    "projectId", projectId.toString(),
                    "name", "Update Expense",
                    "budgetedCost", 500,
                    "expenseDate", LocalDate.now().toString());
            HttpHeaders h = authJsonHeaders();
            HttpEntity<Map<String, Object>> createE = new HttpEntity<>(createBody, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/expenses",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            Map<String, Object> updateBody = Map.of(
                    "projectId", projectId.toString(),
                    "name", "Updated Expense",
                    "budgetedCost", 1500,
                    "expenseDate", LocalDate.now().toString());
            HttpEntity<Map<String, Object>> updateE = new HttpEntity<>(updateBody, h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/expenses/" + id,
                    HttpMethod.PUT, updateE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== FUNDING SOURCES =====================

    @Nested
    @DisplayName("Funding Sources")
    class FundingSourceTests {

        @Test
        @DisplayName("POST /v1/funding-sources - create")
        void createFundingSource_returns201() {
            CreateFundingSourceRequest req = new CreateFundingSourceRequest(
                    "GoI Budget", "Central funding", "FS-001",
                    new BigDecimal("100000000"), null, null);
            HttpEntity<CreateFundingSourceRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/funding-sources", HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /v1/funding-sources - list")
        void listFundingSources_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/funding-sources", HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /v1/funding-sources/{id} - get by ID")
        void getFundingSource_returns200() {
            CreateFundingSourceRequest req = new CreateFundingSourceRequest(
                    "FS Get " + System.currentTimeMillis(), "desc", "FS-GET",
                    new BigDecimal("1000"), null, null);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<CreateFundingSourceRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/funding-sources", HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/funding-sources/" + id, HttpMethod.GET, getE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== PROJECT FUNDING =====================

    @Nested
    @DisplayName("Project Funding")
    class ProjectFundingTests {

        @Test
        @DisplayName("GET /projects/{id}/funding - get funding")
        void getFunding_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/funding",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== FINANCIAL PERIODS =====================

    @Nested
    @DisplayName("Financial Periods")
    class FinancialPeriodTests {

        @Test
        @DisplayName("POST /v1/financial-periods - create")
        void createPeriod_returns201() {
            CreateFinancialPeriodRequest req = new CreateFinancialPeriodRequest(
                    "FY-" + System.currentTimeMillis(),
                    LocalDate.now(), LocalDate.now().plusMonths(3), "QUARTER", 1);
            HttpEntity<CreateFinancialPeriodRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/financial-periods", HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /v1/financial-periods - list")
        void listPeriods_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/financial-periods", HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /v1/financial-periods/open - open periods")
        void listOpenPeriods_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/financial-periods/open", HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== RA BILLS =====================

    @Nested
    @DisplayName("RA Bills")
    class RaBillTests {

        @Test
        @DisplayName("POST /projects/{id}/ra-bills - create")
        void createRaBill_returns201() {
            CreateRaBillRequest req = new CreateRaBillRequest(
                    projectId, null, null, "RA-001",
                    LocalDate.now(), LocalDate.now().plusMonths(1),
                    new BigDecimal("100000"), null, null, null, null, null,
                    new BigDecimal("100000"), null, null);
            HttpEntity<CreateRaBillRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/ra-bills",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /projects/{id}/ra-bills - list")
        void listRaBills_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/ra-bills",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ===================== CASH FLOW =====================

    @Nested
    @DisplayName("Cash Flow")
    class CashFlowTests {

        @Test
        @DisplayName("POST /projects/{id}/cash-flow - create")
        void createCashFlow_returns201() {
            CreateCashFlowForecastRequest req = new CreateCashFlowForecastRequest(
                    projectId, "2026-Q1",
                    new BigDecimal("1000000"), null, null, null, null, null);
            HttpEntity<CreateCashFlowForecastRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/cash-flow",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /projects/{id}/cash-flow - list")
        void listCashFlow_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/cash-flow",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /projects/{id}/cost-summary")
        void getCostSummary_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/cost-summary",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /projects/{id}/cost-periods")
        void getCostPeriods_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/cost-periods",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /projects/{id}/cost-forecast")
        void getCostForecast_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/cost-forecast",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
