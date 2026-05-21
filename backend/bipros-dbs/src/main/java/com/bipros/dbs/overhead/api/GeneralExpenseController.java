package com.bipros.dbs.overhead.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.security.SecurityContextHelper;
import com.bipros.dbs.overhead.api.dto.GeneralExpenseMonthlyEntryDto;
import com.bipros.dbs.overhead.api.dto.GeneralExpensePlanItemDto;
import com.bipros.dbs.overhead.api.dto.MonthlyActualsResponse;
import com.bipros.dbs.overhead.api.dto.MonthlyEntryUpsertRequest;
import com.bipros.dbs.overhead.api.dto.PlanItemUpsertRequest;
import com.bipros.dbs.overhead.domain.model.GeneralExpenseMonthlyEntry;
import com.bipros.dbs.overhead.domain.model.GeneralExpensePlanItem;
import com.bipros.dbs.overhead.service.GeneralExpenseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST API for Section G "General Expenses" — plan items + monthly actuals.
 * Mirrors the existing {@code DbsController} security posture (no
 * {@code @PreAuthorize}; the project-gate filter handles access control).
 */
@Slf4j
@RestController
@RequestMapping("/v1/projects/{projectId}/general-expenses")
@RequiredArgsConstructor
public class GeneralExpenseController {

    private final GeneralExpenseService service;
    private final SecurityContextHelper securityContextHelper;

    // ── plan items ──────────────────────────────────────────────────────────

    @GetMapping("/plan-items")
    public ResponseEntity<ApiResponse<List<GeneralExpensePlanItemDto>>> listPlanItems(
        @PathVariable UUID projectId) {
        List<GeneralExpensePlanItemDto> body = service.listPlanItems(projectId).stream()
            .map(GeneralExpensePlanItemDto::from)
            .toList();
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    @PostMapping("/plan-items")
    public ResponseEntity<ApiResponse<GeneralExpensePlanItemDto>> createPlanItem(
        @PathVariable UUID projectId,
        @RequestBody PlanItemUpsertRequest req) {
        GeneralExpensePlanItem saved = service.createPlanItem(projectId, req.toEntity());
        return ResponseEntity.ok(ApiResponse.ok(GeneralExpensePlanItemDto.from(saved)));
    }

    @PutMapping("/plan-items/{itemId}")
    public ResponseEntity<ApiResponse<GeneralExpensePlanItemDto>> updatePlanItem(
        @PathVariable UUID projectId,
        @PathVariable UUID itemId,
        @RequestBody PlanItemUpsertRequest req) {
        GeneralExpensePlanItem saved = service.updatePlanItem(projectId, itemId, req.toEntity());
        return ResponseEntity.ok(ApiResponse.ok(GeneralExpensePlanItemDto.from(saved)));
    }

    @DeleteMapping("/plan-items/{itemId}")
    public ResponseEntity<ApiResponse<Void>> deletePlanItem(
        @PathVariable UUID projectId,
        @PathVariable UUID itemId) {
        service.deletePlanItem(projectId, itemId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ── monthly actuals ────────────────────────────────────────────────────

    @GetMapping("/actuals")
    public ResponseEntity<ApiResponse<MonthlyActualsResponse>> getActuals(
        @PathVariable UUID projectId,
        @RequestParam Integer yearMonth) {
        List<GeneralExpensePlanItem> plan = service.listPlanItems(projectId);
        Map<UUID, GeneralExpenseMonthlyEntry> entries = service.listActuals(projectId, yearMonth).stream()
            .collect(Collectors.toMap(GeneralExpenseMonthlyEntry::getPlanItemId, e -> e));
        List<MonthlyActualsResponse.Row> rows = plan.stream()
            .map(p -> new MonthlyActualsResponse.Row(
                GeneralExpensePlanItemDto.from(p),
                entries.containsKey(p.getId())
                    ? GeneralExpenseMonthlyEntryDto.from(entries.get(p.getId()))
                    : null
            ))
            .toList();
        BigDecimal total = service.monthlyTotal(projectId, yearMonth);
        return ResponseEntity.ok(ApiResponse.ok(
            new MonthlyActualsResponse(yearMonth, total, rows)
        ));
    }

    @PutMapping("/actuals/{planItemId}")
    public ResponseEntity<ApiResponse<GeneralExpenseMonthlyEntryDto>> upsertActual(
        @PathVariable UUID projectId,
        @PathVariable UUID planItemId,
        @RequestParam Integer yearMonth,
        @RequestBody MonthlyEntryUpsertRequest req) {
        UUID userId;
        try {
            userId = securityContextHelper.getCurrentUserId();
        } catch (RuntimeException ex) {
            // SecurityContextHelper throws IllegalStateException when no auth is
            // present and IllegalArgumentException when the username isn't a UUID
            // (e.g. seeded "admin" user). The id is informational here — fall
            // back to null rather than rejecting the write.
            userId = null;
        }
        GeneralExpenseMonthlyEntry saved = service.upsertMonthly(
            projectId, planItemId, yearMonth, req.toEntity(), userId);
        return ResponseEntity.ok(ApiResponse.ok(GeneralExpenseMonthlyEntryDto.from(saved)));
    }

    @DeleteMapping("/actuals/{planItemId}")
    public ResponseEntity<ApiResponse<Void>> deleteActual(
        @PathVariable UUID projectId,
        @PathVariable UUID planItemId,
        @RequestParam Integer yearMonth) {
        service.deleteMonthly(projectId, planItemId, yearMonth);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
