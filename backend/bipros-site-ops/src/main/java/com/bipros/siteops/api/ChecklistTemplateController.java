package com.bipros.siteops.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.siteops.application.dto.ChecklistTemplateResponse;
import com.bipros.siteops.application.service.ChecklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/checklist-templates")
public class ChecklistTemplateController {

    private final ChecklistService checklistService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'CHECKLIST.READ')")
    public ResponseEntity<ApiResponse<List<ChecklistTemplateResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(checklistService.listTemplates()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CHECKLIST.READ')")
    public ResponseEntity<ApiResponse<ChecklistTemplateResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(checklistService.getTemplate(id)));
    }
}
