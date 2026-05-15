package com.bipros.siteops.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.siteops.application.dto.CreateShiftHandoverRequest;
import com.bipros.siteops.application.dto.ShiftHandoverResponse;
import com.bipros.siteops.application.service.ShiftHandoverService;
import com.bipros.siteops.domain.model.Shift;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/projects/{projectId}/shift-handovers")
public class ShiftHandoverController {

    private final ShiftHandoverService shiftHandoverService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SHIFT_HANDOVER.CREATE')")
    public ResponseEntity<ApiResponse<ShiftHandoverResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateShiftHandoverRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(shiftHandoverService.create(projectId, request)));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SHIFT_HANDOVER.READ')")
    public ResponseEntity<ApiResponse<List<ShiftHandoverResponse>>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate shiftDate,
            @RequestParam(required = false) Shift shift) {
        return ResponseEntity.ok(ApiResponse.ok(shiftHandoverService.list(projectId, shiftDate, shift)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SHIFT_HANDOVER.READ')")
    public ResponseEntity<ApiResponse<ShiftHandoverResponse>> detail(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(shiftHandoverService.getById(projectId, id)));
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SHIFT_HANDOVER.CREATE')")
    public ResponseEntity<ApiResponse<ShiftHandoverResponse>> acknowledge(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(shiftHandoverService.acknowledge(projectId, id)));
    }
}
