package com.bipros.admin.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.admin.application.dto.CreateCurrencyRequest;
import com.bipros.admin.application.dto.CurrencyDto;
import com.bipros.admin.application.service.CurrencyService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/admin/currencies")
@RequiredArgsConstructor
public class CurrencyController {

    private final CurrencyService currencyService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<CurrencyDto>> createCurrency(
        @Valid @RequestBody CreateCurrencyRequest request) {
        CurrencyDto currency = currencyService.createCurrency(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(currency));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<CurrencyDto>> getCurrency(@PathVariable UUID id) {
        CurrencyDto currency = currencyService.getCurrency(id);
        return ResponseEntity.ok(ApiResponse.ok(currency));
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<CurrencyDto>>> listCurrencies() {
        List<CurrencyDto> currencies = currencyService.listCurrencies();
        return ResponseEntity.ok(ApiResponse.ok(currencies));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<CurrencyDto>> updateCurrency(
        @PathVariable UUID id,
        @Valid @RequestBody CreateCurrencyRequest request) {
        CurrencyDto currency = currencyService.updateCurrency(id, request);
        return ResponseEntity.ok(ApiResponse.ok(currency));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> deleteCurrency(@PathVariable UUID id) {
        currencyService.deleteCurrency(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{code}/set-base")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> setBaseCurrency(@PathVariable String code) {
        currencyService.setBaseCurrency(code);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/convert")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<BigDecimal>> convertCurrency(
        @RequestParam BigDecimal amount,
        @RequestParam String from,
        @RequestParam String to) {
        BigDecimal converted = currencyService.convert(amount, from, to);
        return ResponseEntity.ok(ApiResponse.ok(converted));
    }
}
