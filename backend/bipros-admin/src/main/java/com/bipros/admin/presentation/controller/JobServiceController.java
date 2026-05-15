package com.bipros.admin.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.admin.application.dto.JobServiceDto;
import com.bipros.admin.application.service.JobServiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/admin/jobs")
@RequiredArgsConstructor
public class JobServiceController {

    private final JobServiceService jobServiceService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<JobServiceDto>>> listJobs() {
        List<JobServiceDto> jobs = jobServiceService.listJobServices();
        return ResponseEntity.ok(ApiResponse.ok(jobs));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<JobServiceDto>> getJob(@PathVariable UUID id) {
        JobServiceDto job = jobServiceService.getJobService(id);
        return ResponseEntity.ok(ApiResponse.ok(job));
    }

    @GetMapping("/{id}/status")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<JobServiceDto>> getJobStatus(@PathVariable UUID id) {
        JobServiceDto job = jobServiceService.getJobStatus(id);
        return ResponseEntity.ok(ApiResponse.ok(job));
    }

    @PostMapping("/trigger")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<JobServiceDto>> triggerJob(
        @RequestParam String name,
        @RequestParam(required = false) UUID projectId) {
        JobServiceDto job = jobServiceService.triggerJob(name, projectId);
        return ResponseEntity.ok(ApiResponse.ok(job));
    }
}
