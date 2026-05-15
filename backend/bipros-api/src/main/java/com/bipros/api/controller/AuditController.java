package com.bipros.api.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.model.AuditLog;
import com.bipros.common.model.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/audit")
@PreAuthorize("hasPermission(null, 'ADMIN_USER.READ')")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    /**
     * Get audit logs with optional filtering by entity type, entity ID, and user ID.
     *
     * @param entityType optional entity type filter (e.g., "Project")
     * @param entityId optional entity ID filter
     * @param userId optional user ID filter
     * @param page page number (0-indexed, default 0)
     * @param size page size (default 20)
     * @return paginated audit logs
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<AuditLog>>> getAuditLogs(
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) UUID entityId,
        @RequestParam(required = false) UUID userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp"));
        List<AuditLog> logs;

        if (entityType != null && entityId != null) {
            // Filter by entity type and ID
            logs = auditLogRepository.findByEntityTypeAndEntityId(entityType, entityId);
        } else if (userId != null) {
            // Filter by user ID
            logs = auditLogRepository.findByUserId(userId);
        } else if (entityType != null) {
            // Filter by entity type only
            logs = auditLogRepository.findByEntityTypeOrderByTimestampDesc(entityType);
        } else {
            // Return all logs (sorted by timestamp descending)
            logs = auditLogRepository.findAll(pageable).getContent();
        }

        return ResponseEntity.ok(ApiResponse.ok(logs));
    }

    /**
     * Get audit logs for a specific entity.
     *
     * @param entityType the entity type
     * @param entityId the entity ID
     * @return audit logs for the entity
     */
    @GetMapping("/entity")
    public ResponseEntity<ApiResponse<List<AuditLog>>> getEntityAuditLogs(
        @RequestParam String entityType,
        @RequestParam UUID entityId
    ) {
        List<AuditLog> logs = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDesc(entityType, entityId);
        return ResponseEntity.ok(ApiResponse.ok(logs));
    }

    /**
     * Get audit logs for a specific user.
     *
     * @param userId the user ID
     * @return audit logs for the user
     */
    @GetMapping("/user")
    public ResponseEntity<ApiResponse<List<AuditLog>>> getUserAuditLogs(
        @RequestParam UUID userId
    ) {
        List<AuditLog> logs = auditLogRepository.findByUserIdOrderByTimestampDesc(userId);
        return ResponseEntity.ok(ApiResponse.ok(logs));
    }
}
