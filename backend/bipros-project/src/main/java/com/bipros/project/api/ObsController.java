package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.dto.PagedResponse;
import com.bipros.project.application.dto.CreateEpsNodeRequest;
import com.bipros.project.application.dto.EpsNodeResponse;
import com.bipros.project.application.dto.NodeSearchResultResponse;
import com.bipros.project.application.dto.UpdateEpsNodeRequest;
import com.bipros.project.application.service.ObsService;
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
@RequestMapping("/v1/obs")
@RequiredArgsConstructor
public class ObsController {

    private final ObsService obsService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<List<EpsNodeResponse>>> getTree() {
        List<EpsNodeResponse> tree = obsService.getTree();
        return ResponseEntity.ok(ApiResponse.ok(tree));
    }

    @GetMapping("/search")
    @PreAuthorize("hasPermission(null, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<PagedResponse<NodeSearchResultResponse>>> search(
        @RequestParam("q") String q,
        @PageableDefault(size = 25, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(obsService.search(q, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<EpsNodeResponse>> getNode(@PathVariable UUID id) {
        EpsNodeResponse response = obsService.getNode(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'PROJECT.CREATE')")
    public ResponseEntity<ApiResponse<EpsNodeResponse>> createNode(@Valid @RequestBody CreateEpsNodeRequest request) {
        EpsNodeResponse response = obsService.createNode(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<EpsNodeResponse>> updateNode(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateEpsNodeRequest request) {
        EpsNodeResponse response = obsService.updateNode(id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'PROJECT.DELETE')")
    public ResponseEntity<Void> deleteNode(@PathVariable UUID id) {
        obsService.deleteNode(id);
        return ResponseEntity.noContent().build();
    }
}
