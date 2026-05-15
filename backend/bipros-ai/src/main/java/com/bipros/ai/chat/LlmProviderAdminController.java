package com.bipros.ai.chat;

import com.bipros.ai.provider.LlmProviderService;
import com.bipros.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/llm-providers")
@RequiredArgsConstructor
public class LlmProviderAdminController {

    private final LlmProviderService providerService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.READ')")
    public ResponseEntity<ApiResponse<List<LlmProviderConfigResponse>>> list() {
        List<LlmProviderConfigResponse> list = providerService.findAll().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.READ')")
    public ResponseEntity<ApiResponse<LlmProviderConfigResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(toResponse(providerService.findById(id))));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<ApiResponse<LlmProviderConfigResponse>> create(
            @Valid @RequestBody LlmProviderService.CreateLlmProviderRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(toResponse(providerService.create(request))));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<ApiResponse<LlmProviderConfigResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody LlmProviderService.UpdateLlmProviderRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(toResponse(providerService.update(id, request))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        providerService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<ApiResponse<LlmProviderService.ProviderTestResponse>> test(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(providerService.testProvider(id)));
    }

    private LlmProviderConfigResponse toResponse(com.bipros.ai.provider.LlmProviderConfig c) {
        return new LlmProviderConfigResponse(
                c.getId(),
                c.getName(),
                c.getBaseUrl(),
                c.getModel(),
                c.getMaxTokens(),
                c.getTemperature(),
                c.getTimeoutMs(),
                c.getAuthScheme(),
                c.isSupportsNativeTools(),
                c.isDefault(),
                c.isActive()
        );
    }

    public record LlmProviderConfigResponse(UUID id, String name, String baseUrl, String model,
                                            Integer maxTokens, java.math.BigDecimal temperature, Integer timeoutMs,
                                            String authScheme, boolean supportsNativeTools,
                                            boolean isDefault, boolean isActive) {
    }
}
