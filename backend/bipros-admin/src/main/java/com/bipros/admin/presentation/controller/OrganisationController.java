package com.bipros.admin.presentation.controller;

import com.bipros.admin.application.dto.CreateOrganisationRequest;
import com.bipros.admin.application.dto.OrganisationDto;
import com.bipros.admin.application.service.OrganisationService;
import com.bipros.admin.domain.model.OrganisationType;
import com.bipros.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/organisations")
@RequiredArgsConstructor
public class OrganisationController {

    private final OrganisationService organisationService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_ORG.READ')")
    public ResponseEntity<ApiResponse<List<OrganisationDto>>> list(
            @RequestParam(value = "type", required = false) OrganisationType type) {
        List<OrganisationDto> result = (type != null)
                ? organisationService.listByType(type)
                : organisationService.listAll();
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_ORG.READ')")
    public ResponseEntity<ApiResponse<OrganisationDto>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(organisationService.get(id)));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_ORG.CREATE')")
    public ResponseEntity<ApiResponse<OrganisationDto>> create(
            @Valid @RequestBody CreateOrganisationRequest request) {
        OrganisationDto created = organisationService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_ORG.UPDATE')")
    public ResponseEntity<ApiResponse<OrganisationDto>> update(
            @PathVariable UUID id,
            @Valid @RequestBody CreateOrganisationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(organisationService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_ORG.DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        organisationService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * Replace the set of projects this organisation is associated with. Body: {@code {"projectIds":
     * [uuid, uuid]}}. Supplying an empty list clears all associations.
     */
    @PostMapping("/{id}/projects")
    @PreAuthorize("hasPermission(null, 'ADMIN_ORG.UPDATE')")
    public ResponseEntity<ApiResponse<OrganisationDto>> assignProjects(
            @PathVariable UUID id,
            @RequestBody Map<String, List<UUID>> body) {
        List<UUID> projectIds = body.getOrDefault("projectIds", List.of());
        return ResponseEntity.ok(ApiResponse.ok(
            organisationService.assignToProjects(id, projectIds)));
    }

    /** Alias for {@link #list} at {@code /v1/contractors} to match the PMS MasterData doc. */
    @GetMapping("/as-contractors")
    @PreAuthorize("hasPermission(null, 'ADMIN_ORG.READ')")
    public ResponseEntity<ApiResponse<List<OrganisationDto>>> listContractors() {
        return ResponseEntity.ok(ApiResponse.ok(organisationService.listAll()));
    }
}
