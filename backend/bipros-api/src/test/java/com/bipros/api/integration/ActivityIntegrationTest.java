package com.bipros.api.integration;

import com.bipros.activity.application.dto.CreateActivityRequest;
import com.bipros.activity.application.dto.CreateRelationshipRequest;
import com.bipros.activity.application.dto.UpdateRelationshipRequest;
import com.bipros.activity.application.dto.CreateActivityStepRequest;
import com.bipros.activity.domain.model.RelationshipType;
import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateEpsNodeRequest;
import com.bipros.project.application.dto.CreateProjectRequest;
import com.bipros.project.application.dto.CreateWbsNodeRequest;
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
@DisplayName("Activity Integration Tests")
class ActivityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("bipros_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @Autowired
    private TestRestTemplate restTemplate;

    private String token;
    private UUID projectId;
    private UUID wbsNodeId;

    @BeforeEach
    void setUp() {
        String suffix = "ACT" + System.currentTimeMillis();
        RegisterRequest reg = new RegisterRequest(
                "actuser" + suffix, "actuser" + suffix + "@example.com",
                "testPassword123!", "ACT", "User");
        restTemplate.postForEntity("/v1/auth/register", reg, ApiResponse.class);
        LoginRequest login = new LoginRequest("actuser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> resp = restTemplate.postForEntity(
                "/v1/auth/login", login, ApiResponse.class);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        token = (String) data.get("accessToken");

        HttpHeaders h = authJsonHeaders();
        CreateEpsNodeRequest epsReq = new CreateEpsNodeRequest(
                "EPS-ACT-" + suffix, "EPS Activity " + suffix, null, null);
        HttpEntity<CreateEpsNodeRequest> epsE = new HttpEntity<>(epsReq, h);
        ResponseEntity<ApiResponse> epsR = restTemplate.exchange("/v1/eps", HttpMethod.POST, epsE, ApiResponse.class);
        Map<String, Object> epsD = (Map<String, Object>) epsR.getBody().data();
        UUID epsId = UUID.fromString((String) epsD.get("id"));

        CreateProjectRequest projReq = new CreateProjectRequest(
                "PRJ-ACT-" + suffix, "Project Activity " + suffix, "desc",
                epsId, null, LocalDate.now(), LocalDate.now().plusMonths(12),
                5, null, null, null, null, null, null, null, null, null,
                null);
        HttpEntity<CreateProjectRequest> projE = new HttpEntity<>(projReq, h);
        ResponseEntity<ApiResponse> projR = restTemplate.exchange("/v1/projects", HttpMethod.POST, projE, ApiResponse.class);
        Map<String, Object> projD = (Map<String, Object>) projR.getBody().data();
        projectId = UUID.fromString((String) projD.get("id"));

        CreateWbsNodeRequest wbsReq = new CreateWbsNodeRequest(
                "WBS-ACT-" + suffix, "WBS Activity " + suffix, null, projectId, null);
        HttpEntity<CreateWbsNodeRequest> wbsE = new HttpEntity<>(wbsReq, h);
        ResponseEntity<ApiResponse> wbsR = restTemplate.exchange(
                "/v1/projects/" + projectId + "/wbs", HttpMethod.POST, wbsE, ApiResponse.class);
        Map<String, Object> wbsD = (Map<String, Object>) wbsR.getBody().data();
        wbsNodeId = UUID.fromString((String) wbsD.get("id"));
    }

    private HttpHeaders authJsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ===================== ACTIVITY TESTS =====================

    @Nested
    @DisplayName("POST /v1/projects/{projectId}/activities")
    class CreateActivityTests {

        @Test
        @DisplayName("should create activity")
        void createActivity_returns201() {
            String suffix = "CA" + System.currentTimeMillis();
            CreateActivityRequest req = new CreateActivityRequest(
                    "ACT-" + suffix, "Activity " + suffix, "Test activity",
                    projectId, wbsNodeId, null, null, null,
                    10.0, LocalDate.now(), LocalDate.now().plusDays(10),
                    null, null, null, null, null, null, null, null);

            HttpEntity<CreateActivityRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("should reject activity with blank code")
        void createActivityBlankCode_returns400() {
            CreateActivityRequest req = new CreateActivityRequest(
                    "", "No Code", null, projectId, wbsNodeId,
                    null, null, null, 10.0,
                    LocalDate.now(), LocalDate.now().plusDays(10),
                    null, null, null, null, null, null, null, null);

            HttpEntity<CreateActivityRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("GET /v1/projects/{projectId}/activities")
    class ListActivitiesTests {

        @Test
        @DisplayName("should list activities for project")
        void listActivities_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("GET /v1/projects/{projectId}/activities/{activityId}")
    class GetActivityTests {

        @Test
        @DisplayName("should get activity by ID")
        void getActivity_returns200() {
            String suffix = "GA" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateActivityRequest req = new CreateActivityRequest(
                    "ACT-G-" + suffix, "Get Activity " + suffix, null,
                    projectId, wbsNodeId, null, null, null,
                    10.0, LocalDate.now(), LocalDate.now().plusDays(10),
                    null, null, null, null, null, null, null, null);
            HttpEntity<CreateActivityRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities/" + id,
                    HttpMethod.GET, getE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("PUT /v1/projects/{projectId}/activities/{activityId}")
    class UpdateActivityTests {

        @Test
        @DisplayName("should update activity")
        void updateActivity_returns200() {
            String suffix = "UA" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateActivityRequest req = new CreateActivityRequest(
                    "ACT-U-" + suffix, "Update Activity " + suffix, null,
                    projectId, wbsNodeId, null, null, null,
                    10.0, LocalDate.now(), LocalDate.now().plusDays(10),
                    null, null, null, null, null, null, null, null);
            HttpEntity<CreateActivityRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            Map<String, Object> updateBody = Map.of("name", "Updated Activity Name");
            HttpEntity<Map<String, Object>> updateE = new HttpEntity<>(updateBody, h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities/" + id,
                    HttpMethod.PUT, updateE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("should change percentCompleteType from DURATION to PHYSICAL and back")
        void updatePercentCompleteType_roundTrips() {
            String suffix = "PCT" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            // Create as default (DURATION).
            CreateActivityRequest req = new CreateActivityRequest(
                    "ACT-PCT-" + suffix, "PercentCompleteType " + suffix, null,
                    projectId, wbsNodeId, null, null, null,
                    10.0, LocalDate.now(), LocalDate.now().plusDays(10),
                    null, null, null, null, null, null, null, null);
            HttpEntity<CreateActivityRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities",
                    HttpMethod.POST, createE, ApiResponse.class);
            assertThat(createR.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Map<String, Object> created = (Map<String, Object>) createR.getBody().data();
            String id = (String) created.get("id");
            assertThat(created.get("percentCompleteType")).isEqualTo("DURATION");

            // PUT to flip to PHYSICAL.
            HttpEntity<Map<String, Object>> physE = new HttpEntity<>(
                    Map.of("percentCompleteType", "PHYSICAL"), h);
            ResponseEntity<ApiResponse> physR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities/" + id,
                    HttpMethod.PUT, physE, ApiResponse.class);
            assertThat(physR.getStatusCode()).isEqualTo(HttpStatus.OK);

            // GET — confirm persisted.
            ResponseEntity<ApiResponse> getR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities/" + id,
                    HttpMethod.GET, new HttpEntity<>(h), ApiResponse.class);
            Map<String, Object> got = (Map<String, Object>) getR.getBody().data();
            assertThat(got.get("percentCompleteType")).isEqualTo("PHYSICAL");

            // PUT back to UNITS — confirm any → any transition is allowed.
            HttpEntity<Map<String, Object>> unitsE = new HttpEntity<>(
                    Map.of("percentCompleteType", "UNITS"), h);
            ResponseEntity<ApiResponse> unitsR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities/" + id,
                    HttpMethod.PUT, unitsE, ApiResponse.class);
            assertThat(unitsR.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> unitsData = (Map<String, Object>) unitsR.getBody().data();
            assertThat(unitsData.get("percentCompleteType")).isEqualTo("UNITS");
        }
    }

    @Nested
    @DisplayName("DELETE /v1/projects/{projectId}/activities/{activityId}")
    class DeleteActivityTests {

        @Test
        @DisplayName("should delete activity")
        void deleteActivity_returns204() {
            String suffix = "DA" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateActivityRequest req = new CreateActivityRequest(
                    "ACT-D-" + suffix, "Delete Activity " + suffix, null,
                    projectId, wbsNodeId, null, null, null,
                    10.0, LocalDate.now(), LocalDate.now().plusDays(10),
                    null, null, null, null, null, null, null, null);
            HttpEntity<CreateActivityRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> delE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities/" + id,
                    HttpMethod.DELETE, delE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);
        }
    }

    // ===================== RELATIONSHIP TESTS =====================

    @Nested
    @DisplayName("Activity Relationships")
    class RelationshipTests {

        private UUID createActivity(String code, String name) {
            CreateActivityRequest req = new CreateActivityRequest(
                    code, name, null, projectId, wbsNodeId,
                    null, null, null, 10.0,
                    LocalDate.now(), LocalDate.now().plusDays(10),
                    null, null, null, null, null, null, null, null);
            HttpEntity<CreateActivityRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities",
                    HttpMethod.POST, e, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) resp.getBody().data();
            return UUID.fromString((String) d.get("id"));
        }

        @Test
        @DisplayName("POST /relationships - create relationship")
        void createRelationship_returns201() {
            String suffix = "REL" + System.currentTimeMillis();
            UUID predId = createActivity("ACT-PRED-" + suffix, "Predecessor " + suffix);
            UUID succId = createActivity("ACT-SUCC-" + suffix, "Successor " + suffix);

            CreateRelationshipRequest req = new CreateRelationshipRequest(
                    predId, succId, RelationshipType.FINISH_TO_START, 0.0);
            HttpEntity<CreateRelationshipRequest> e = new HttpEntity<>(req, authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/relationships",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /relationships - list all relationships")
        void getRelationships_returns200() {
            HttpEntity<Void> e = new HttpEntity<>(authJsonHeaders());
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/relationships",
                    HttpMethod.GET, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("GET /relationships/{id} - get by ID")
        void getRelationship_returns200() {
            String suffix = "RGL" + System.currentTimeMillis();
            UUID predId = createActivity("ACT-RP-" + suffix, "R Pred " + suffix);
            UUID succId = createActivity("ACT-RS-" + suffix, "R Succ " + suffix);

            CreateRelationshipRequest req = new CreateRelationshipRequest(
                    predId, succId, RelationshipType.FINISH_TO_START, 0.0);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<CreateRelationshipRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/relationships",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/relationships/" + id,
                    HttpMethod.GET, getE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("PUT /relationships/{id} - update relationship")
        void updateRelationship_returns200() {
            String suffix = "RUP" + System.currentTimeMillis();
            UUID predId = createActivity("ACT-RUP-" + suffix, "R Up Pred " + suffix);
            UUID succId = createActivity("ACT-RUS-" + suffix, "R Up Succ " + suffix);

            HttpHeaders h = authJsonHeaders();
            CreateRelationshipRequest req = new CreateRelationshipRequest(
                    predId, succId, RelationshipType.FINISH_TO_START, 0.0);
            HttpEntity<CreateRelationshipRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/relationships",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            UpdateRelationshipRequest updateReq = new UpdateRelationshipRequest("FINISH_TO_START", 2.0);
            HttpEntity<UpdateRelationshipRequest> updateE = new HttpEntity<>(updateReq, h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/relationships/" + id,
                    HttpMethod.PUT, updateE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("DELETE /relationships/{id} - delete relationship")
        void deleteRelationship_returns204() {
            String suffix = "RD" + System.currentTimeMillis();
            UUID predId = createActivity("ACT-RP-D-" + suffix, "R Del Pred " + suffix);
            UUID succId = createActivity("ACT-RS-D-" + suffix, "R Del Succ " + suffix);

            HttpHeaders h = authJsonHeaders();
            CreateRelationshipRequest req = new CreateRelationshipRequest(
                    predId, succId, RelationshipType.FINISH_TO_START, 0.0);
            HttpEntity<CreateRelationshipRequest> createE = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> createR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/relationships",
                    HttpMethod.POST, createE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) createR.getBody().data();
            String id = (String) d.get("id");

            HttpEntity<Void> delE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/relationships/" + id,
                    HttpMethod.DELETE, delE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);
        }
    }

    // ===================== ACTIVITY STEP TESTS =====================

    @Nested
    @DisplayName("Activity Steps")
    class ActivityStepTests {

        @Test
        @DisplayName("POST /activities/{activityId}/steps - create step")
        void createStep_returns201() {
            String suffix = "AS" + System.currentTimeMillis();
            CreateActivityRequest actReq = new CreateActivityRequest(
                    "ACT-AS-" + suffix, "Step Activity " + suffix, null,
                    projectId, wbsNodeId, null, null, null,
                    10.0, LocalDate.now(), LocalDate.now().plusDays(10),
                    null, null, null, null, null, null, null, null);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<CreateActivityRequest> actE = new HttpEntity<>(actReq, h);
            ResponseEntity<ApiResponse> actR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities",
                    HttpMethod.POST, actE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) actR.getBody().data();
            String actId = (String) d.get("id");

            CreateActivityStepRequest req = new CreateActivityStepRequest(
                    "Step 1", "First step", 1.0);
            HttpEntity<CreateActivityStepRequest> e = new HttpEntity<>(req, h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/activities/" + actId + "/steps",
                    HttpMethod.POST, e, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("GET /activities/{activityId}/steps - list steps")
        void listSteps_returns200() {
            String suffix = "AL" + System.currentTimeMillis();
            CreateActivityRequest actReq = new CreateActivityRequest(
                    "ACT-AL-" + suffix, "List Steps " + suffix, null,
                    projectId, wbsNodeId, null, null, null,
                    10.0, LocalDate.now(), LocalDate.now().plusDays(10),
                    null, null, null, null, null, null, null, null);
            HttpHeaders h = authJsonHeaders();
            HttpEntity<CreateActivityRequest> actE = new HttpEntity<>(actReq, h);
            ResponseEntity<ApiResponse> actR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities",
                    HttpMethod.POST, actE, ApiResponse.class);
            Map<String, Object> d = (Map<String, Object>) actR.getBody().data();
            String actId = (String) d.get("id");

            HttpEntity<Void> getE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/activities/" + actId + "/steps",
                    HttpMethod.GET, getE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("PUT /activities/{activityId}/steps/{stepId} - update step")
        void updateStep_returns200() {
            String suffix = "AUP" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateActivityRequest actReq = new CreateActivityRequest(
                    "ACT-AUP-" + suffix, "Up Step " + suffix, null,
                    projectId, wbsNodeId, null, null, null,
                    10.0, LocalDate.now(), LocalDate.now().plusDays(10),
                    null, null, null, null, null, null, null, null);
            HttpEntity<CreateActivityRequest> actE = new HttpEntity<>(actReq, h);
            ResponseEntity<ApiResponse> actR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities",
                    HttpMethod.POST, actE, ApiResponse.class);
            Map<String, Object> actD = (Map<String, Object>) actR.getBody().data();
            String actId = (String) actD.get("id");

            CreateActivityStepRequest stepReq = new CreateActivityStepRequest("Step", "desc", 1.0);
            HttpEntity<CreateActivityStepRequest> stepE = new HttpEntity<>(stepReq, h);
            ResponseEntity<ApiResponse> stepR = restTemplate.exchange(
                    "/v1/activities/" + actId + "/steps",
                    HttpMethod.POST, stepE, ApiResponse.class);
            Map<String, Object> stepD = (Map<String, Object>) stepR.getBody().data();
            String stepId = (String) stepD.get("id");

            CreateActivityStepRequest updateReq = new CreateActivityStepRequest(
                    "Updated Step", "updated desc", 2.0);
            HttpEntity<CreateActivityStepRequest> updateE = new HttpEntity<>(updateReq, h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/activities/" + actId + "/steps/" + stepId,
                    HttpMethod.PUT, updateE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("DELETE /activities/{activityId}/steps/{stepId} - delete step")
        void deleteStep_returns204() {
            String suffix = "AD" + System.currentTimeMillis();
            HttpHeaders h = authJsonHeaders();
            CreateActivityRequest actReq = new CreateActivityRequest(
                    "ACT-AD-" + suffix, "Del Step " + suffix, null,
                    projectId, wbsNodeId, null, null, null,
                    10.0, LocalDate.now(), LocalDate.now().plusDays(10),
                    null, null, null, null, null, null, null, null);
            HttpEntity<CreateActivityRequest> actE = new HttpEntity<>(actReq, h);
            ResponseEntity<ApiResponse> actR = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/activities",
                    HttpMethod.POST, actE, ApiResponse.class);
            Map<String, Object> actD = (Map<String, Object>) actR.getBody().data();
            String actId = (String) actD.get("id");

            CreateActivityStepRequest stepReq = new CreateActivityStepRequest("Step", "desc", 1.0);
            HttpEntity<CreateActivityStepRequest> stepE = new HttpEntity<>(stepReq, h);
            ResponseEntity<ApiResponse> stepR = restTemplate.exchange(
                    "/v1/activities/" + actId + "/steps",
                    HttpMethod.POST, stepE, ApiResponse.class);
            Map<String, Object> stepD = (Map<String, Object>) stepR.getBody().data();
            String stepId = (String) stepD.get("id");

            HttpEntity<Void> delE = new HttpEntity<>(h);
            ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                    "/v1/activities/" + actId + "/steps/" + stepId,
                    HttpMethod.DELETE, delE, ApiResponse.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);
        }
    }
}
