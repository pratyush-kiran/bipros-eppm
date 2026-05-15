package com.bipros.integration.controller;

import com.bipros.integration.dto.IntegrationConfigDto;
import com.bipros.integration.service.IntegrationConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/integrations")
@RequiredArgsConstructor
public class IntegrationConfigController {

    private final IntegrationConfigService integrationConfigService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.READ')")
    public ResponseEntity<List<IntegrationConfigDto>> listAll() {
        return ResponseEntity.ok(integrationConfigService.listAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.READ')")
    public ResponseEntity<IntegrationConfigDto> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(integrationConfigService.getById(id));
    }

    @GetMapping("/system/{systemCode}")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.READ')")
    public ResponseEntity<IntegrationConfigDto> getBySystemCode(@PathVariable String systemCode) {
        return ResponseEntity.ok(integrationConfigService.getBySystemCode(systemCode));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<IntegrationConfigDto> create(@Valid @RequestBody IntegrationConfigDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(integrationConfigService.create(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<IntegrationConfigDto> update(
        @PathVariable UUID id,
        @Valid @RequestBody IntegrationConfigDto dto
    ) {
        return ResponseEntity.ok(integrationConfigService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        integrationConfigService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
