package com.bipros.contract.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.contract.application.dto.ContractAttachmentResponse;
import com.bipros.contract.application.dto.UploadContractAttachmentRequest;
import com.bipros.contract.application.service.ContractAttachmentService;
import com.bipros.contract.application.service.ContractAttachmentService.AttachmentDownload;
import com.bipros.contract.domain.model.AttachmentEntityType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for typed attachments at every level of the contract bounded context.
 * Backed by a single polymorphic table; see {@link com.bipros.contract.domain.model.ContractAttachment}.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/contracts/{contractId}")
@RequiredArgsConstructor
public class ContractAttachmentController {

    private final ContractAttachmentService attachmentService;

    // ---------------------------------------------------------------- contract-level

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CONTRACT.UPDATE')")
    public ResponseEntity<ApiResponse<ContractAttachmentResponse>> uploadContractAttachment(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @Valid @RequestPart("metadata") UploadContractAttachmentRequest metadata,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        ContractAttachmentResponse response = attachmentService.upload(
            projectId, contractId, AttachmentEntityType.CONTRACT, contractId,
            metadata, file, currentUser(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /** Returns every attachment under the contract regardless of entity type (for the unified Attachments tab). */
    @GetMapping("/attachments")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<List<ContractAttachmentResponse>>> listAllAttachments(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId) {
        return ResponseEntity.ok(ApiResponse.ok(
            attachmentService.listAllForContract(projectId, contractId)));
    }

    // ---------------------------------------------------------------- milestone-level

    @PostMapping(value = "/milestones/{milestoneId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CONTRACT.UPDATE')")
    public ResponseEntity<ApiResponse<ContractAttachmentResponse>> uploadMilestoneAttachment(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @PathVariable UUID milestoneId,
            @Valid @RequestPart("metadata") UploadContractAttachmentRequest metadata,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        ContractAttachmentResponse response = attachmentService.upload(
            projectId, contractId, AttachmentEntityType.MILESTONE, milestoneId,
            metadata, file, currentUser(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping("/milestones/{milestoneId}/attachments")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<List<ContractAttachmentResponse>>> listMilestoneAttachments(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @PathVariable UUID milestoneId) {
        return ResponseEntity.ok(ApiResponse.ok(
            attachmentService.list(projectId, contractId, AttachmentEntityType.MILESTONE, milestoneId)));
    }

    // ---------------------------------------------------------------- variation-order-level

    @PostMapping(value = "/variation-orders/{voId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CONTRACT.UPDATE')")
    public ResponseEntity<ApiResponse<ContractAttachmentResponse>> uploadVariationOrderAttachment(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @PathVariable UUID voId,
            @Valid @RequestPart("metadata") UploadContractAttachmentRequest metadata,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        ContractAttachmentResponse response = attachmentService.upload(
            projectId, contractId, AttachmentEntityType.VARIATION_ORDER, voId,
            metadata, file, currentUser(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping("/variation-orders/{voId}/attachments")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<List<ContractAttachmentResponse>>> listVariationOrderAttachments(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @PathVariable UUID voId) {
        return ResponseEntity.ok(ApiResponse.ok(
            attachmentService.list(projectId, contractId, AttachmentEntityType.VARIATION_ORDER, voId)));
    }

    // ---------------------------------------------------------------- performance-bond-level

    @PostMapping(value = "/bonds/{bondId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CONTRACT.UPDATE')")
    public ResponseEntity<ApiResponse<ContractAttachmentResponse>> uploadPerformanceBondAttachment(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @PathVariable UUID bondId,
            @Valid @RequestPart("metadata") UploadContractAttachmentRequest metadata,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        ContractAttachmentResponse response = attachmentService.upload(
            projectId, contractId, AttachmentEntityType.PERFORMANCE_BOND, bondId,
            metadata, file, currentUser(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping("/bonds/{bondId}/attachments")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CONTRACT.READ')")
    public ResponseEntity<ApiResponse<List<ContractAttachmentResponse>>> listPerformanceBondAttachments(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @PathVariable UUID bondId) {
        return ResponseEntity.ok(ApiResponse.ok(
            attachmentService.list(projectId, contractId, AttachmentEntityType.PERFORMANCE_BOND, bondId)));
    }

    // ---------------------------------------------------------------- shared download/delete

    @GetMapping("/attachments/{attachmentId}/download")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CONTRACT.READ')")
    public ResponseEntity<Resource> download(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @PathVariable UUID attachmentId,
            @RequestParam(value = "disposition", defaultValue = "attachment") String disposition) {
        AttachmentDownload dl = attachmentService.download(projectId, contractId, attachmentId);
        ContentDisposition cd = ContentDisposition
            .builder("inline".equalsIgnoreCase(disposition) ? "inline" : "attachment")
            .filename(dl.fileName())
            .build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(dl.mimeType()))
            .contentLength(dl.fileSize())
            .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
            .body(dl.resource());
    }

    @DeleteMapping("/attachments/{attachmentId}")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'CONTRACT.DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @PathVariable UUID attachmentId) {
        attachmentService.delete(projectId, contractId, attachmentId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private static String currentUser(Authentication authentication) {
        return authentication != null ? authentication.getName() : "SYSTEM";
    }
}
