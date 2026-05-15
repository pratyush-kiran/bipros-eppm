package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateCorridorCodeRequest;
import com.bipros.project.application.dto.CorridorCodeResponse;
import com.bipros.project.application.service.CorridorCodeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/corridor-code")
@RequiredArgsConstructor
public class CorridorCodeController {

    private final CorridorCodeService corridorCodeService;

    @GetMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<CorridorCodeResponse>> getCorridorCode(@PathVariable UUID projectId) {
        CorridorCodeResponse response = corridorCodeService.getByProject(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<ApiResponse<CorridorCodeResponse>> generateCorridorCode(
        @PathVariable UUID projectId,
        @Valid @RequestBody CreateCorridorCodeRequest request) {
        CorridorCodeResponse response = corridorCodeService.generateCode(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }
}
