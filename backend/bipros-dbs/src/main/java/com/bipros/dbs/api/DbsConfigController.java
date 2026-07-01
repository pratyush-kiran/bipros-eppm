package com.bipros.dbs.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.dbs.api.dto.DbsConfigResponse;
import com.bipros.dbs.config.DbsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only accessor for app-wide DBS tunables (no UI to edit them — they live in
 * {@code application.yml} under {@code bipros.dbs}). Global, not project-scoped: the fuel ratio is
 * a single application setting. Authenticated-only (no {@code @PreAuthorize}) so DPR users
 * (supervisors) can read it without the {@code ADMIN_MASTER.READ} that {@code /v1/admin/settings}
 * requires.
 */
@RestController
@RequestMapping("/v1/dbs/config")
@RequiredArgsConstructor
public class DbsConfigController {

    private final DbsProperties properties;

    @GetMapping
    public ResponseEntity<ApiResponse<DbsConfigResponse>> getConfig() {
        return ResponseEntity.ok(
            ApiResponse.ok(new DbsConfigResponse(properties.getFuelMachineryCostRatio())));
    }
}
