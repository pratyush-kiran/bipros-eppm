package com.bipros.api.integration;

import com.bipros.activity.application.dto.UpdateActivityRequest;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityRelationship;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.RelationshipType;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.dto.ApiResponse;
import com.bipros.security.application.dto.AuthResponse;
import com.bipros.security.application.dto.LoginRequest;
import com.bipros.security.application.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Activity predecessor validation integration tests")
class ActivityPredecessorValidationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("bipros_test")
        .withUsername("postgres")
        .withPassword("postgres");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ActivityRelationshipRepository relationshipRepository;

    private String authToken;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        authToken = authenticate();
        projectId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    }

    @Test
    @DisplayName("Updating successor percentComplete > 0 with unfinished FS predecessor returns 422")
    void updateSuccessorProgress_withUnfinishedPredecessor_returns422() {
        // 1. Create predecessor (ACT-7.1)
        Activity pred = new Activity();
        pred.setProjectId(projectId);
        pred.setWbsNodeId(UUID.randomUUID());
        pred.setCode("ACT-7.1");
        pred.setName("Miscellaneous & Provisional");
        pred.setStatus(ActivityStatus.NOT_STARTED);
        pred.setPercentComplete(0.0);
        pred = activityRepository.save(pred);

        // 2. Create successor (ACT-7.2)
        Activity succ = new Activity();
        succ.setProjectId(projectId);
        succ.setWbsNodeId(UUID.randomUUID());
        succ.setCode("ACT-7.2");
        succ.setName("Activity Design Phase");
        succ.setStatus(ActivityStatus.NOT_STARTED);
        succ.setPercentComplete(0.0);
        succ = activityRepository.save(succ);

        // 3. Create FS + 2d relationship
        ActivityRelationship rel = new ActivityRelationship();
        rel.setProjectId(projectId);
        rel.setPredecessorActivityId(pred.getId());
        rel.setSuccessorActivityId(succ.getId());
        rel.setRelationshipType(RelationshipType.FINISH_TO_START);
        rel.setLag(2.0);
        rel.setIsExternal(false);
        relationshipRepository.save(rel);

        // 4. Try to update successor with percentComplete = 12
        UpdateActivityRequest request = new UpdateActivityRequest(
            null, null, null, null, null, null,
            null, null, null,
            12.0, null, null, null,
            null, null, null, null, null, null, null, null, null, null
        , null, null, null, null, null);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<UpdateActivityRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<ApiResponse> response = restTemplate.exchange(
            "/v1/projects/" + projectId + "/activities/" + succ.getId(),
            HttpMethod.PUT,
            entity,
            ApiResponse.class
        );

        // Should be blocked
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("PREDECESSOR_NOT_SATISFIED");
    }

    @Test
    @DisplayName("Updating successor name only with unfinished FS predecessor succeeds")
    void updateSuccessorNameOnly_withUnfinishedPredecessor_succeeds() {
        Activity pred = new Activity();
        pred.setProjectId(projectId);
        pred.setWbsNodeId(UUID.randomUUID());
        pred.setCode("ACT-7.1b");
        pred.setName("Predecessor");
        pred.setStatus(ActivityStatus.NOT_STARTED);
        pred.setPercentComplete(0.0);
        pred = activityRepository.save(pred);

        Activity succ = new Activity();
        succ.setProjectId(projectId);
        succ.setWbsNodeId(UUID.randomUUID());
        succ.setCode("ACT-7.2b");
        succ.setName("Successor");
        succ.setStatus(ActivityStatus.NOT_STARTED);
        succ.setPercentComplete(0.0);
        succ = activityRepository.save(succ);

        ActivityRelationship rel = new ActivityRelationship();
        rel.setProjectId(projectId);
        rel.setPredecessorActivityId(pred.getId());
        rel.setSuccessorActivityId(succ.getId());
        rel.setRelationshipType(RelationshipType.FINISH_TO_START);
        rel.setLag(0.0);
        rel.setIsExternal(false);
        relationshipRepository.save(rel);

        UpdateActivityRequest request = new UpdateActivityRequest(
            "Renamed Successor", null, null, null, null, null,
            null, null, null,
            null, null, null, null,
            null, null, null, null, null, null, null, null, null, null
        , null, null, null, null, null);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<UpdateActivityRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<ApiResponse> response = restTemplate.exchange(
            "/v1/projects/" + projectId + "/activities/" + succ.getId(),
            HttpMethod.PUT,
            entity,
            ApiResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String authenticate() {
        // Register
        RegisterRequest registerRequest = new RegisterRequest(
            "predtest" + System.currentTimeMillis(),
            "predtest" + System.currentTimeMillis() + "@example.com",
            "testPassword123!",
            "Test",
            "User"
        );
        restTemplate.postForEntity("/v1/auth/register", registerRequest, ApiResponse.class);

        // Login
        LoginRequest loginRequest = new LoginRequest(registerRequest.username(), registerRequest.password());
        ResponseEntity<ApiResponse> loginResponse = restTemplate.postForEntity(
            "/v1/auth/login", loginRequest, ApiResponse.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) loginResponse.getBody().data();
        return (String) data.get("accessToken");
    }
}
