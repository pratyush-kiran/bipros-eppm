package com.bipros.siteops.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.siteops.application.dto.AttendanceResponse;
import com.bipros.siteops.application.dto.AttendanceSummary;
import com.bipros.siteops.application.dto.CreateAttendanceRequest;
import com.bipros.siteops.application.dto.UpdateAttendanceRequest;
import com.bipros.siteops.application.service.AttendanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/projects/{projectId}/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ATTENDANCE.CREATE')")
    public ResponseEntity<ApiResponse<AttendanceResponse>> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateAttendanceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(attendanceService.create(projectId, request)));
    }

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ATTENDANCE.READ')")
    public ResponseEntity<ApiResponse<List<AttendanceResponse>>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(attendanceService.list(projectId, from, to)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ATTENDANCE.UPDATE')")
    public ResponseEntity<ApiResponse<AttendanceResponse>> update(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAttendanceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(attendanceService.update(projectId, id, request)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ATTENDANCE.APPROVE')")
    public ResponseEntity<ApiResponse<AttendanceResponse>> approve(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(attendanceService.approve(projectId, id)));
    }

    @GetMapping("/summary")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'ATTENDANCE.READ')")
    public ResponseEntity<ApiResponse<List<AttendanceSummary>>> summary(
            @PathVariable UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(attendanceService.summary(projectId, from, to)));
    }
}
