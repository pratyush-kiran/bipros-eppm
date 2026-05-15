package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.dto.PagedResponse;
import com.bipros.project.application.dto.CreateEpsNodeRequest;
import com.bipros.project.application.dto.EpsNodeResponse;
import com.bipros.project.application.dto.NodeSearchResultResponse;
import com.bipros.project.application.dto.UpdateEpsNodeRequest;
import com.bipros.project.application.service.EpsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/eps")
@RequiredArgsConstructor
public class EpsController {

    private final EpsService epsService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<List<EpsNodeResponse>>> getTree() {
        List<EpsNodeResponse> tree = epsService.getTree();
        return ResponseEntity.ok(ApiResponse.ok(tree));
    }

    @GetMapping("/search")
    @PreAuthorize("hasPermission(null, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<PagedResponse<NodeSearchResultResponse>>> search(
        @RequestParam("q") String q,
        @PageableDefault(size = 25, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(epsService.search(q, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<EpsNodeResponse>> getNode(@PathVariable UUID id) {
        EpsNodeResponse response = epsService.getNode(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'PROJECT.CREATE')")
    public ResponseEntity<ApiResponse<EpsNodeResponse>> createNode(@Valid @RequestBody CreateEpsNodeRequest request) {
        EpsNodeResponse response = epsService.createNode(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<EpsNodeResponse>> updateNode(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateEpsNodeRequest request) {
        EpsNodeResponse response = epsService.updateNode(id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PatchMapping("/{id}/move")
    @PreAuthorize("hasPermission(null, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<EpsNodeResponse>> moveNode(
        @PathVariable UUID id,
        @RequestBody(required = false) java.util.Map<String, String> body) {
        UUID newParentId = null;
        if (body != null && body.get("parentId") != null && !body.get("parentId").isBlank()) {
            newParentId = UUID.fromString(body.get("parentId"));
        }
        EpsNodeResponse response = epsService.moveNode(id, newParentId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'PROJECT.DELETE')")
    public ResponseEntity<Void> deleteNode(@PathVariable UUID id) {
        epsService.deleteNode(id);
        return ResponseEntity.noContent().build();
    }
}
