package com.bipros.importexport.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.importexport.application.dto.ExportProjectRequest;
import com.bipros.importexport.application.dto.ImportExportJobResponse;
import com.bipros.importexport.application.dto.ImportExportLogResponse;
import com.bipros.importexport.application.service.ImportExportService;
import com.bipros.importexport.domain.model.ImportExportFormat;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/import-export")
@RequiredArgsConstructor
public class ImportExportController {

  private final ImportExportService importExportService;

  @PostMapping("/export")
  @PreAuthorize("hasPermission(null, 'PROJECT.EXPORT')")
  public ApiResponse<ImportExportJobResponse> exportProject(
      @Valid @RequestBody ExportProjectRequest request) throws Exception {
    return ApiResponse.ok(
        importExportService.exportProject(request.projectId(), request.format()));
  }

  @PostMapping("/import")
  @PreAuthorize("hasPermission(null, 'PROJECT.UPDATE')")
  public ApiResponse<ImportExportJobResponse> importProject(
      @RequestParam("file") MultipartFile file,
      @RequestParam ImportExportFormat format) throws Exception {
    return ApiResponse.ok(importExportService.importProject(file, format));
  }

  @GetMapping("/jobs")
  @PreAuthorize("hasPermission(null, 'PROJECT.EXPORT')")
  public ApiResponse<List<ImportExportJobResponse>> listJobs() {
    return ApiResponse.ok(importExportService.listJobs());
  }

  @GetMapping("/jobs/{jobId}")
  @PreAuthorize("hasPermission(null, 'PROJECT.EXPORT')")
  public ApiResponse<ImportExportJobResponse> getJobStatus(@PathVariable UUID jobId) {
    return ApiResponse.ok(importExportService.getJobStatus(jobId));
  }

  @GetMapping("/jobs/{jobId}/logs")
  @PreAuthorize("hasPermission(null, 'PROJECT.EXPORT')")
  public ApiResponse<List<ImportExportLogResponse>> getJobLogs(@PathVariable UUID jobId) {
    return ApiResponse.ok(importExportService.getJobLogs(jobId));
  }

  @GetMapping("/jobs/{jobId}/download")
  @PreAuthorize("hasPermission(null, 'PROJECT.EXPORT')")
  public ResponseEntity<byte[]> downloadExportedFile(@PathVariable UUID jobId) throws Exception {
    var job = importExportService.getJobStatus(jobId);
    Path filePath = Paths.get(job.filePath()).normalize();
    Path allowedDir = Paths.get(System.getProperty("bipros.export.dir", "/tmp/bipros-exports")).normalize();
    if (!filePath.startsWith(allowedDir)) {
      throw new SecurityException("Access denied: file path outside allowed directory");
    }
    byte[] fileContent = Files.readAllBytes(filePath);

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + job.fileName() + "\"")
        .header(HttpHeaders.CONTENT_TYPE, getMediaType(job.format()))
        .body(fileContent);
  }

  @GetMapping("/projects/{projectId}/export/p6xml")
  @PreAuthorize("hasPermission(null, 'PROJECT.EXPORT')")
  public ResponseEntity<String> exportP6Xml(@PathVariable UUID projectId) throws Exception {
    String content = importExportService.exportP6Xml(projectId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"project_" + projectId + "_p6.xml\"")
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE)
        .body(content);
  }

  @GetMapping("/projects/{projectId}/export/msp")
  @PreAuthorize("hasPermission(null, 'PROJECT.EXPORT')")
  public ResponseEntity<String> exportMspXml(@PathVariable UUID projectId) throws Exception {
    String content = importExportService.exportMspXml(projectId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"project_" + projectId + "_msp.xml\"")
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE)
        .body(content);
  }

  @GetMapping("/projects/{projectId}/export/excel")
  @PreAuthorize("hasPermission(null, 'PROJECT.EXPORT')")
  public ResponseEntity<byte[]> exportExcel(@PathVariable UUID projectId) throws Exception {
    byte[] content = importExportService.exportExcel(projectId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"project_" + projectId + ".xlsx\"")
        .header(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        .body(content);
  }

  @GetMapping("/projects/{projectId}/export/csv")
  @PreAuthorize("hasPermission(null, 'PROJECT.EXPORT')")
  public ResponseEntity<String> exportCsv(@PathVariable UUID projectId) throws Exception {
    String content = importExportService.exportCsv(projectId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"project_" + projectId + ".csv\"")
        .header(HttpHeaders.CONTENT_TYPE, "text/csv")
        .body(content);
  }

  @PostMapping("/projects/import/xer")
  @PreAuthorize("hasPermission(null, 'PROJECT.UPDATE')")
  public ApiResponse<ImportExportJobResponse> importXer(@RequestParam("file") MultipartFile file) throws Exception {
    return ApiResponse.ok(importExportService.importProject(file, ImportExportFormat.XER));
  }

  private String getMediaType(ImportExportFormat format) {
    return switch (format) {
      case P6XML, MSP_XML -> MediaType.APPLICATION_XML_VALUE;
      case EXCEL -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      case CSV -> "text/csv";
      case XER -> "text/plain";
    };
  }
}
