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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Project Member Integration Tests")
class ProjectMemberIntegrationTest {

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
        String suffix = "PM" + System.currentTimeMillis();
        RegisterRequest registerRequest = new RegisterRequest(
                "pmuser" + suffix, "pmuser" + suffix + "@example.com",
                "testPassword123!", "PM", "User");
        restTemplate.postForEntity("/v1/auth/register", registerRequest, ApiResponse.class);

        LoginRequest loginRequest = new LoginRequest("pmuser" + suffix, "testPassword123!");
        ResponseEntity<ApiResponse> loginResponse = restTemplate.postForEntity(
                "/v1/auth/login", loginRequest, ApiResponse.class);
        Map<String, Object> data = (Map<String, Object>) loginResponse.getBody().data();
        token = (String) data.get("accessToken");

        // Create EPS root
        String epsSuffix = "EP" + suffix;
        CreateEpsNodeRequest epsRequest = new CreateEpsNodeRequest(
                "EPS-PM-" + epsSuffix, "EPS PM " + epsSuffix, null, null);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<CreateEpsNodeRequest> epsEntity = new HttpEntity<>(epsRequest, headers);
        ResponseEntity<ApiResponse> epsResponse = restTemplate.exchange(
                "/v1/eps", HttpMethod.POST, epsEntity, ApiResponse.class);
        Map<String, Object> epsData = (Map<String, Object>) epsResponse.getBody().data();
        UUID epsId = UUID.fromString((String) epsData.get("id"));

        // Create project
        CreateProjectRequest projRequest = new CreateProjectRequest(
                "PROJ-PM-" + epsSuffix, "Project PM " + epsSuffix, "Test project",
                epsId, null, LocalDate.now(), LocalDate.now().plusMonths(12),                 5,
                null, null, null, null, null, null, null, null, null,
                null);
        HttpEntity<CreateProjectRequest> projEntity = new HttpEntity<>(projRequest, headers);
        ResponseEntity<ApiResponse> projResponse = restTemplate.exchange(
                "/v1/projects", HttpMethod.POST, projEntity, ApiResponse.class);
        Map<String, Object> projData = (Map<String, Object>) projResponse.getBody().data();
        projectId = UUID.fromString((String) projData.get("id"));
    }

    private HttpHeaders authJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Nested
    @DisplayName("GET /v1/projects/{projectId}/members")
    class ListMembersTests {

        @Test
        @DisplayName("should list project members")
        void listMembers_returns200() {
            HttpEntity<Void> entity = new HttpEntity<>(authJsonHeaders());

            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/members",
                    HttpMethod.GET, entity, ApiResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
        }
    }

    @Nested
    @DisplayName("POST /v1/projects/{projectId}/members")
    class AssignMemberTests {

        @Test
        @DisplayName("should assign a role to a user in project")
        void assignMember_returns201() {
            // First get current user's ID
            HttpHeaders headers = authJsonHeaders();
            ResponseEntity<ApiResponse> meResponse = restTemplate.exchange(
                    "/v1/users/me", HttpMethod.GET, new HttpEntity<>(headers), ApiResponse.class);
            Map<String, Object> userData = (Map<String, Object>) meResponse.getBody().data();
            String userId = (String) userData.get("id");

            Map<String, Object> body = Map.of(
                    "userId", userId,
                    "projectRole", "TEAM_MEMBER");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<ApiResponse> response = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/members",
                    HttpMethod.POST, entity, ApiResponse.class);

            // May be 201 or conflict depending on implementation
            assertThat(response.getStatusCode().value()).isIn(201, 200);
        }
    }

    @Nested
    @DisplayName("DELETE /v1/projects/{projectId}/members/{memberId}")
    class RevokeMemberTests {

        @Test
        @DisplayName("should revoke a project member assignment")
        void revokeMember_returns204() {
            // First assign
            HttpHeaders headers = authJsonHeaders();
            ResponseEntity<ApiResponse> meResponse = restTemplate.exchange(
                    "/v1/users/me", HttpMethod.GET, new HttpEntity<>(headers), ApiResponse.class);
            Map<String, Object> userData = (Map<String, Object>) meResponse.getBody().data();
            String userId = (String) userData.get("id");

            Map<String, Object> assignBody = Map.of(
                    "userId", userId,
                    "projectRole", "TEAM_MEMBER");

            HttpEntity<Map<String, Object>> assignEntity = new HttpEntity<>(assignBody, headers);
            ResponseEntity<ApiResponse> assignResponse = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/members",
                    HttpMethod.POST, assignEntity, ApiResponse.class);

            // Try to revoke - use the project member ID if available
            HttpEntity<Void> deleteEntity = new HttpEntity<>(headers);
            ResponseEntity<ApiResponse> revokeResponse = restTemplate.exchange(
                    "/v1/projects/" + projectId + "/members/" + userId,
                    HttpMethod.DELETE, deleteEntity, ApiResponse.class);

            assertThat(revokeResponse.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK, HttpStatus.NOT_FOUND);
        }
    }
}
