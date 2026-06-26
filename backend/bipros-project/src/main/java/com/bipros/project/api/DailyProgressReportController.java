package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateDailyProgressReportRequest;
import com.bipros.project.application.dto.DailyProgressReportResponse;
import com.bipros.project.application.dto.DprApprovalActionRequest;
import com.bipros.project.application.dto.DprAttachmentResponse;
import com.bipros.project.application.dto.DprSummaryResponse;
import com.bipros.project.application.dto.DprVoiceNoteResponse;
import com.bipros.project.application.dto.SupervisorOption;
import com.bipros.project.application.dto.DprPage;
import com.bipros.project.application.dto.UpdateDailyProgressReportRequest;
import com.bipros.project.application.service.DailyProgressReportService;
import com.bipros.project.application.service.DprAttachmentService;
import com.bipros.project.application.service.DprVoiceNoteService;
import com.bipros.project.infrastructure.storage.VoiceNoteStorage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/dpr")
@RequiredArgsConstructor
@Slf4j
public class DailyProgressReportController {

  private final DailyProgressReportService service;
  private final DprAttachmentService attachmentService;
  private final DprVoiceNoteService voiceNoteService;
  private final VoiceNoteStorage voiceNoteStorage;
  private final com.bipros.project.application.service.DprProductivityPreviewService previewService;

  @PostMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.CREATE')")
  public ResponseEntity<ApiResponse<DailyProgressReportResponse>> create(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateDailyProgressReportRequest request) {
    log.info("POST /v1/projects/{}/dpr - date={}, activity={}", projectId, request.reportDate(), request.activityName());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.create(projectId, request)));
  }

  @PostMapping("/bulk")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.CREATE')")
  public ResponseEntity<ApiResponse<List<DailyProgressReportResponse>>> createBulk(
      @PathVariable UUID projectId,
      @Valid @RequestBody List<CreateDailyProgressReportRequest> requests) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.createBulk(projectId, requests)));
  }

  @GetMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.READ')")
  public ResponseEntity<ApiResponse<DprPage>> list(
      @PathVariable UUID projectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String activity,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate before,
      @RequestParam(defaultValue = "14") int days) {
    return ResponseEntity.ok(ApiResponse.ok(service.listPaged(projectId, from, to, activity, before, days)));
  }

  @GetMapping("/{id}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.READ')")
  public ResponseEntity<ApiResponse<DailyProgressReportResponse>> get(
      @PathVariable UUID projectId,
      @PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(projectId, id)));
  }

  /**
   * Distinct supervisors who actually filed at least one DPR in the optional date window.
   * Powers the Supervisor filter dropdown on the Capacity Utilization page so only people with
   * data are listed.
   */
  /**
   * Productivity preview — read-only, never writes. Returns expected output from manpower,
   * equipment, and the bottleneck (min) given the rows already entered on the form. Called
   * from the DPR form on debounced row changes.
   */
  @PostMapping("/activities/{activityId}/productivity-preview")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.READ')")
  public ResponseEntity<ApiResponse<com.bipros.project.application.dto.ProductivityPreviewResponse>>
      productivityPreview(
          @PathVariable UUID projectId,
          @PathVariable UUID activityId,
          @RequestBody com.bipros.project.application.dto.ProductivityPreviewRequest body) {
    return ResponseEntity.ok(
        ApiResponse.ok(previewService.preview(projectId, activityId, body)));
  }

  @GetMapping("/supervisors-used")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.READ')")
  public ResponseEntity<ApiResponse<List<SupervisorOption>>> supervisorsUsed(
      @PathVariable UUID projectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
    return ResponseEntity.ok(ApiResponse.ok(service.listSupervisorsUsed(projectId, fromDate, toDate)));
  }

  @PutMapping("/{id}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.UPDATE')")
  public ResponseEntity<ApiResponse<DailyProgressReportResponse>> update(
      @PathVariable UUID projectId,
      @PathVariable UUID id,
      @Valid @RequestBody UpdateDailyProgressReportRequest request) {
    log.info("PUT /v1/projects/{}/dpr/{} - date={}, qty={}", projectId, id, request.reportDate(), request.qtyExecuted());
    return ResponseEntity.ok(ApiResponse.ok(service.update(projectId, id, request)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.DELETE')")
  public ResponseEntity<ApiResponse<Void>> delete(
      @PathVariable UUID projectId,
      @PathVariable UUID id) {
    service.delete(projectId, id);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  // ─── Approval actions ─────────────────────────────────────────────────────────────

  @PostMapping("/{id}/approve")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.APPROVE')")
  public ResponseEntity<ApiResponse<DailyProgressReportResponse>> approve(
      @PathVariable UUID projectId,
      @PathVariable UUID id,
      @RequestBody(required = false) DprApprovalActionRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(service.approve(projectId, id, request)));
  }

  @PostMapping("/{id}/reject")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.APPROVE')")
  public ResponseEntity<ApiResponse<DailyProgressReportResponse>> reject(
      @PathVariable UUID projectId,
      @PathVariable UUID id,
      @RequestBody DprApprovalActionRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(service.reject(projectId, id, request)));
  }

  @PostMapping("/{id}/revoke")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.APPROVE')")
  public ResponseEntity<ApiResponse<DailyProgressReportResponse>> revoke(
      @PathVariable UUID projectId,
      @PathVariable UUID id,
      @RequestBody(required = false) DprApprovalActionRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(service.revoke(projectId, id, request)));
  }

  // ─── Approval queue ────────────────────────────────────────────────────────────────

  @GetMapping("/approvals/pending")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.APPROVE')")
  public ResponseEntity<ApiResponse<List<DprSummaryResponse>>> listPendingApprovals(
      @PathVariable UUID projectId) {
    return ResponseEntity.ok(ApiResponse.ok(service.listPendingApprovals(projectId)));
  }

  @GetMapping("/approvals/unassigned")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.APPROVE')")
  public ResponseEntity<ApiResponse<List<DprSummaryResponse>>> listUnassignedPending(
      @PathVariable UUID projectId) {
    return ResponseEntity.ok(ApiResponse.ok(service.listUnassignedPending(projectId)));
  }

  // ─── Photo attachments ──────────────────────────────────────────────────────────

  /**
   * Upload one or more photos against a DPR row. {@code captions} is parallel to {@code files}
   * and each entry is optional; pass empty strings or omit indices to leave a caption blank.
   *
   * <p>{@code captions} uses {@link RequestParam} (not {@link RequestPart}) so plain-text parts
   * are read as strings without invoking JSON message conversion (which would fail when the
   * part has no Content-Type — the default for {@code FormData.append('captions','…')} and
   * {@code curl -F 'captions=…'}).
   */
  @PostMapping(value = "/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.UPDATE')")
  public ResponseEntity<ApiResponse<List<DprAttachmentResponse>>> uploadPhotos(
      @PathVariable UUID projectId,
      @PathVariable UUID id,
      @RequestPart("files") MultipartFile[] files,
      @RequestParam(value = "captions", required = false) String[] captions) {
    log.info("POST /v1/projects/{}/dpr/{}/photos - {} file(s)", projectId, id, files == null ? 0 : files.length);
    List<MultipartFile> fileList = files == null ? List.of() : Arrays.asList(files);
    List<String> captionList = captions == null ? List.of() : Arrays.asList(captions);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(attachmentService.addAll(projectId, id, fileList, captionList)));
  }

  @GetMapping("/{id}/photos")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.READ')")
  public ResponseEntity<ApiResponse<List<DprAttachmentResponse>>> listPhotos(
      @PathVariable UUID projectId,
      @PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(attachmentService.list(projectId, id)));
  }

  @GetMapping("/{id}/photos/{photoId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.READ')")
  public ResponseEntity<Resource> getPhoto(
      @PathVariable UUID projectId,
      @PathVariable UUID id,
      @PathVariable UUID photoId) {
    DprAttachmentService.LoadedPhoto loaded = attachmentService.load(projectId, id, photoId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(loaded.mimeType()))
        .contentLength(loaded.fileSize())
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + loaded.fileName() + "\"")
        .body(loaded.resource());
  }

  @DeleteMapping("/{id}/photos/{photoId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> deletePhoto(
      @PathVariable UUID projectId,
      @PathVariable UUID id,
      @PathVariable UUID photoId) {
    attachmentService.delete(projectId, id, photoId);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  // ─── Voice notes (audio attachments, MinIO-backed) ────────────────────────────────

  /**
   * Upload one or more audio voice notes against a DPR row. {@code captions} and {@code durations}
   * are parallel to {@code files} and each entry is optional. As with photos, these use
   * {@link RequestParam} (not {@link RequestPart}) so the plain-text parts are read as strings
   * without invoking JSON message conversion (which fails when the part has no Content-Type — the
   * default for {@code FormData.append(...)} and {@code curl -F}).
   */
  @PostMapping(value = "/{id}/voice-notes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.UPDATE')")
  public ResponseEntity<ApiResponse<List<DprVoiceNoteResponse>>> uploadVoiceNotes(
      @PathVariable UUID projectId,
      @PathVariable UUID id,
      @RequestPart("files") MultipartFile[] files,
      @RequestParam(value = "captions", required = false) String[] captions,
      @RequestParam(value = "durations", required = false) Integer[] durations) {
    log.info("POST /v1/projects/{}/dpr/{}/voice-notes - {} file(s)", projectId, id, files == null ? 0 : files.length);
    List<MultipartFile> fileList = files == null ? List.of() : Arrays.asList(files);
    List<String> captionList = captions == null ? List.of() : Arrays.asList(captions);
    List<Integer> durationList = durations == null ? List.of() : Arrays.asList(durations);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(voiceNoteService.addAll(projectId, id, fileList, captionList, durationList)));
  }

  @GetMapping("/{id}/voice-notes")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.READ')")
  public ResponseEntity<ApiResponse<List<DprVoiceNoteResponse>>> listVoiceNotes(
      @PathVariable UUID projectId,
      @PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(voiceNoteService.list(projectId, id)));
  }

  @DeleteMapping("/{id}/voice-notes/{voiceNoteId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> deleteVoiceNote(
      @PathVariable UUID projectId,
      @PathVariable UUID id,
      @PathVariable UUID voiceNoteId) {
    voiceNoteService.delete(projectId, id, voiceNoteId);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  /**
   * Stream a voice note's audio with HTTP Range / 206 support so the player can seek without
   * downloading the whole file. {@link StreamingResponseBody} releases the servlet thread during
   * the MinIO→client copy and copies in small chunks, so heap stays flat regardless of file size.
   *
   * <p>No {@code Range} header → 200 + full body + Content-Length. A satisfiable range →
   * 206 + {@code Content-Range: bytes start-end/total} + Content-Length=len. An unsatisfiable
   * range → 416 with a Content-Range of {@code bytes }-slash-{@code total}. {@code Accept-Ranges:
   * bytes} is always set.
   */
  @GetMapping("/{id}/voice-notes/{voiceNoteId}/stream")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'DPR.READ')")
  public ResponseEntity<StreamingResponseBody> streamVoiceNote(
      @PathVariable UUID projectId,
      @PathVariable UUID id,
      @PathVariable UUID voiceNoteId,
      HttpServletRequest request) {
    DprVoiceNoteService.LoadedVoiceNote note = voiceNoteService.loadMeta(projectId, id, voiceNoteId);
    String key = note.storageKey();
    long total = voiceNoteStorage.contentLength(key);
    MediaType mediaType = MediaType.parseMediaType(note.mimeType());
    String contentDisposition = "inline; filename=\"" + note.fileName() + "\"";

    List<HttpRange> ranges = HttpRange.parseRanges(request.getHeader(HttpHeaders.RANGE));
    if (ranges.isEmpty()) {
      // No range — full stream.
      StreamingResponseBody body = out -> {
        try (InputStream in = voiceNoteStorage.openFull(key)) {
          in.transferTo(out);
        }
      };
      return ResponseEntity.ok()
          .header(HttpHeaders.ACCEPT_RANGES, "bytes")
          .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
          .contentType(mediaType)
          .contentLength(total)
          .body(body);
    }

    HttpRange range = ranges.get(0);
    long start = range.getRangeStart(total);
    long end = range.getRangeEnd(total);
    if (start >= total) {
      // Unsatisfiable range.
      return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
          .header(HttpHeaders.ACCEPT_RANGES, "bytes")
          .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
          .build();
    }
    long len = end - start + 1;
    StreamingResponseBody body = out -> {
      try (InputStream in = voiceNoteStorage.openRange(key, start, end)) {
        in.transferTo(out);
      }
    };
    return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total)
        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
        .contentType(mediaType)
        .contentLength(len)
        .body(body);
  }
}
