package com.bipros.integration.controller;

import com.bipros.integration.model.PfmsFundTransfer;
import com.bipros.integration.service.PfmsIntegrationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/pfms")
@RequiredArgsConstructor
public class PfmsController {

    private final PfmsIntegrationService pfmsIntegrationService;

    @PostMapping("/check-fund")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<PfmsFundTransfer> checkFundStatus(
        @PathVariable UUID projectId,
        @NotBlank @RequestParam String sanctionOrderNumber
    ) {
        return ResponseEntity.ok(pfmsIntegrationService.checkAndLogFundStatus(sanctionOrderNumber));
    }

    @PostMapping("/initiate-payment")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<PfmsFundTransfer> initiatePayment(
        @PathVariable UUID projectId,
        @NotBlank @RequestParam String sanctionOrderNumber,
        @NotBlank @RequestParam String beneficiary,
        @NotNull @RequestParam BigDecimal amount,
        @RequestParam(required = false) String purpose
    ) {
        return ResponseEntity.ok(
            pfmsIntegrationService.initiateFundTransfer(
                projectId,
                sanctionOrderNumber,
                beneficiary,
                amount,
                purpose
            )
        );
    }

    @GetMapping("/transfers")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.READ')")
    public ResponseEntity<Page<PfmsFundTransfer>> getFundTransfers(
        @PathVariable UUID projectId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(pfmsIntegrationService.getProjectFundTransfers(projectId, page, size));
    }
}
