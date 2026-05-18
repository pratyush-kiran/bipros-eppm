package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.project.application.dto.ProjectTeamMemberRequest;
import com.bipros.project.application.dto.ProjectTeamMemberResponse;
import com.bipros.project.application.service.ProjectTeamService;
import com.bipros.project.domain.model.ProjectRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/team")
@RequiredArgsConstructor
@Slf4j
public class ProjectTeamController {

    private final ProjectTeamService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectTeamMemberResponse>>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String role) {
        List<ProjectTeamMemberResponse> result = role == null || role.isBlank()
            ? service.listForProject(projectId)
            : service.listByRole(projectId, ProjectRole.valueOf(role.trim().toUpperCase()));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectTeamMemberResponse>> create(
            @PathVariable UUID projectId,
            @RequestBody ProjectTeamMemberRequest request) {
        log.info("POST /v1/projects/{}/team user={} role={}", projectId, request.userId(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.create(projectId, request)));
    }

    @PutMapping("/{memberId}")
    public ResponseEntity<ApiResponse<ProjectTeamMemberResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID memberId,
            @RequestBody ProjectTeamMemberRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(projectId, memberId, request)));
    }

    @DeleteMapping("/{memberId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID memberId) {
        service.delete(projectId, memberId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/resolve/engineer")
    public ResponseEntity<ApiResponse<UUID>> resolveEngineer(
            @PathVariable UUID projectId,
            @RequestParam UUID supervisorUserId) {
        UUID engineerId = service.resolveEngineerFor(projectId, supervisorUserId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "ProjectTeamMember(engineer-for-supervisor)", supervisorUserId));
        return ResponseEntity.ok(ApiResponse.ok(engineerId));
    }

    @GetMapping("/resolve/pm")
    public ResponseEntity<ApiResponse<UUID>> resolvePm(@PathVariable UUID projectId) {
        UUID pmId = service.resolvePmFor(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("ProjectTeamMember(pm)", projectId));
        return ResponseEntity.ok(ApiResponse.ok(pmId));
    }
}
