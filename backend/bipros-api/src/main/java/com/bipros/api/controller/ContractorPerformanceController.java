package com.bipros.api.controller;

import com.bipros.admin.domain.model.Organisation;
import com.bipros.admin.domain.model.OrganisationType;
import com.bipros.admin.domain.repository.OrganisationRepository;
import com.bipros.common.dto.ApiResponse;
import com.bipros.contract.domain.model.Contract;
import com.bipros.contract.domain.model.ContractStatus;
import com.bipros.contract.domain.repository.ContractRepository;
import com.bipros.cost.domain.entity.RaBill;
import com.bipros.cost.domain.entity.SatelliteGate;
import com.bipros.cost.domain.repository.RaBillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Programme-dashboard contractor scorecard endpoint.
 *
 * <p>Derives per-EPC-contractor performance/safety/compliance scores from live
 * data: satellite-gate PASS %, BG validity %, active-contract aggregates.
 * Returns one row per seeded EPC organisation (L&T IDPL, Tata Projects,
 * Afcons, HCC, Dilip Buildcon).
 */
@RestController
@RequestMapping("/v1/analytics")
@PreAuthorize("hasPermission(null, 'REPORT.READ')")
@RequiredArgsConstructor
public class ContractorPerformanceController {

    private final OrganisationRepository organisationRepository;
    private final ContractRepository contractRepository;
    private final RaBillRepository raBillRepository;

    @GetMapping("/contractor-performance")
    public ResponseEntity<ApiResponse<List<ContractorPerformance>>> getContractorPerformance() {
        List<Organisation> epcContractors =
            organisationRepository.findByOrganisationType(OrganisationType.EPC_CONTRACTOR);

        // Preload all contracts and RA bills once; EPC list is tiny (5) but
        // calling the repos per-org would multiply queries unnecessarily.
        List<Contract> allContracts = contractRepository.findAll();
        List<RaBill> allRaBills = raBillRepository.findAll();
        LocalDate today = LocalDate.now();

        List<ContractorPerformance> rows = new ArrayList<>();
        for (Organisation org : epcContractors) {
            rows.add(buildScorecard(org, allContracts, allRaBills, today));
        }

        // Deterministic ordering — by org code — so the dashboard list is stable.
        rows.sort(Comparator.comparing(ContractorPerformance::orgCode));
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    private ContractorPerformance buildScorecard(
        Organisation org,
        List<Contract> allContracts,
        List<RaBill> allRaBills,
        LocalDate today
    ) {
        // Contract.contractorCode links to Organisation.code (string key,
        // not FK — Contract has no contractorOrganisationId column).
        List<Contract> contractorContracts = allContracts.stream()
            .filter(c -> org.getCode().equals(c.getContractorCode()))
            .toList();

        List<Contract> activeContracts = contractorContracts.stream()
            .filter(c -> c.getStatus() == ContractStatus.ACTIVE)
            .toList();

        BigDecimal totalValueCr = activeContracts.stream()
            .map(Contract::getContractValue)
            .filter(v -> v != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── Performance: satellite-gate PASS % across the contractor's RA bills.
        //    Join via contractId (preferred) or fall back to wbsPackageCode when
        //    the bill wasn't linked directly to a contract.
        Set<UUID> contractIds = contractorContracts.stream()
            .map(Contract::getId)
            .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        Set<String> wbsCodes = contractorContracts.stream()
            .map(Contract::getWbsPackageCode)
            .filter(w -> w != null && !w.isBlank())
            .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        long totalBills = allRaBills.stream()
            .filter(b -> (b.getContractId() != null && contractIds.contains(b.getContractId()))
                || (b.getWbsPackageCode() != null && wbsCodes.contains(b.getWbsPackageCode())))
            .count();
        long passBills = allRaBills.stream()
            .filter(b -> (b.getContractId() != null && contractIds.contains(b.getContractId()))
                || (b.getWbsPackageCode() != null && wbsCodes.contains(b.getWbsPackageCode())))
            .filter(b -> b.getSatelliteGate() == SatelliteGate.PASS)
            .count();
        Double performanceScore = totalBills == 0
            ? null
            : round2(passBills * 100.0 / totalBills);

        // ── Safety: data not ingested — always null; frontend shows "n/a".
        Double safetyScore = null;

        // ── Compliance: BG validity % over contracts with a bgExpiry recorded.
        long contractsWithBg = contractorContracts.stream()
            .filter(c -> c.getBgExpiry() != null)
            .count();
        long bgValid = contractorContracts.stream()
            .filter(c -> c.getBgExpiry() != null)
            .filter(c -> !c.getBgExpiry().isBefore(today))
            .count();
        Double complianceScore = contractsWithBg == 0
            ? null
            : round2(bgValid * 100.0 / contractsWithBg);

        return new ContractorPerformance(
            org.getId(),
            org.getCode(),
            org.getName(),
            performanceScore,
            safetyScore,
            complianceScore,
            activeContracts.size(),
            totalValueCr.setScale(2, RoundingMode.HALF_UP)
        );
    }

    private static Double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
