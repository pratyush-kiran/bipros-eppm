package com.bipros.hds.api.admin;

import com.bipros.common.dto.ApiResponse;
import com.bipros.hds.api.dto.CreateHdsDocumentRequest;
import com.bipros.hds.api.dto.HdsDocumentResponse;
import com.bipros.hds.api.dto.UpdateHdsDocumentRequest;
import com.bipros.hds.application.library.HdsLibraryService;
import com.bipros.hds.application.library.dto.CreateHdsDocumentInput;
import com.bipros.hds.application.library.dto.UpdateHdsDocumentInput;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/hds/admin/documents")
@RequiredArgsConstructor
public class HdsDocumentAdminController {

    private final HdsLibraryService library;

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.CREATE')")
    @PostMapping
    public ResponseEntity<ApiResponse<HdsDocumentResponse>> create(@Valid @RequestBody CreateHdsDocumentRequest req) {
        var doc = library.createDocument(new CreateHdsDocumentInput(
            req.title(), req.shortCode(), req.discipline(),
            req.issuingAuthority(), req.country(), req.description()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(HdsDocumentResponse.from(doc)));
    }

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.READ')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<HdsDocumentResponse>>> list() {
        var docs = library.listDocuments().stream().map(HdsDocumentResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok(docs));
    }

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.UPDATE')")
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<HdsDocumentResponse>> update(@PathVariable UUID id,
                                                                   @RequestBody UpdateHdsDocumentRequest req) {
        var doc = library.updateDocument(id, new UpdateHdsDocumentInput(
            req.title(), req.discipline(), req.issuingAuthority(), req.country(), req.description()));
        return ResponseEntity.ok(ApiResponse.ok(HdsDocumentResponse.from(doc)));
    }

    @PreAuthorize("hasPermission(null, 'HDS_LIBRARY.DELETE')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        library.deleteDocument(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
