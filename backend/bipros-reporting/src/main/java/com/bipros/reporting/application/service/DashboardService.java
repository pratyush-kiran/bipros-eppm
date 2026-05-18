package com.bipros.reporting.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.reporting.application.dto.CreateKpiDefinitionRequest;
import com.bipros.reporting.application.dto.DashboardConfigDto;
import com.bipros.reporting.application.dto.KpiDefinitionDto;
import com.bipros.reporting.application.dto.KpiSnapshotDto;
import com.bipros.reporting.domain.model.DashboardConfig;
import com.bipros.reporting.domain.model.KpiDefinition;
import com.bipros.reporting.domain.model.KpiSnapshot;
import com.bipros.reporting.domain.repository.DashboardConfigRepository;
import com.bipros.reporting.domain.repository.KpiDefinitionRepository;
import com.bipros.reporting.domain.repository.KpiSnapshotRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private final DashboardConfigRepository dashboardConfigRepository;
    private final KpiDefinitionRepository kpiDefinitionRepository;
    private final KpiSnapshotRepository kpiSnapshotRepository;

    @PersistenceContext private EntityManager em;

    @Transactional(readOnly = true)
    public List<DashboardConfigDto> listDashboards() {
        return dashboardConfigRepository.findAll()
                .stream()
                .map(DashboardConfigDto::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DashboardConfigDto getDashboardByTier(String tier) {
        var dashboardTier = DashboardConfig.DashboardTier.valueOf(tier);
        var entity = dashboardConfigRepository.findByTier(dashboardTier)
                .orElseThrow(() -> new ResourceNotFoundException("DashboardConfig", tier));
        return DashboardConfigDto.from(entity);
    }

    @Transactional(readOnly = true)
    public List<KpiSnapshotDto> getKpisByTierAndProject(String tier, UUID projectId) {
        var dashboardTier = DashboardConfig.DashboardTier.valueOf(tier);
        var config = dashboardConfigRepository.findByTier(dashboardTier)
                .orElseThrow(() -> new ResourceNotFoundException("DashboardConfig", tier));

        if (projectId == null) {
            return List.of();
        }

        return kpiSnapshotRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(KpiSnapshotDto::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<KpiDefinitionDto> getAllKpiDefinitions() {
        return kpiDefinitionRepository.findAll()
                .stream()
                .filter(k -> k.getIsActive() != null && k.getIsActive())
                .map(KpiDefinitionDto::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public KpiDefinitionDto createKpiDefinition(CreateKpiDefinitionRequest request) {
        if (kpiDefinitionRepository.findByCode(request.code()).isPresent()) {
            throw new BusinessRuleException("KPI_DUPLICATE_CODE",
                    "KPI definition with code " + request.code() + " already exists");
        }

        var entity = new KpiDefinition();
        entity.setName(request.name());
        entity.setCode(request.code());
        entity.setFormula(request.formula());
        entity.setUnit(request.unit());
        entity.setGreenThreshold(request.greenThreshold());
        entity.setAmberThreshold(request.amberThreshold());
        entity.setRedThreshold(request.redThreshold());
        entity.setModuleSource(request.moduleSource());
        entity.setIsActive(request.isActive());

        var saved = kpiDefinitionRepository.save(entity);
        return KpiDefinitionDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<KpiSnapshotDto> getProjectKpiSnapshots(UUID projectId) {
        return kpiSnapshotRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(KpiSnapshotDto::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<KpiSnapshotDto> calculateProjectKpis(UUID projectId) {
        var kpiDefinitions = kpiDefinitionRepository.findByIsActiveTrue();

        var snapshots = kpiDefinitions.stream()
                .map(kpiDef -> {
                    var snapshot = new KpiSnapshot();
                    snapshot.setKpiDefinitionId(kpiDef.getId());
                    snapshot.setProjectId(projectId);
                    snapshot.setCalculatedAt(Instant.now());
                    snapshot.setValue(calculateKpiValue(kpiDef, projectId));
                    snapshot.setStatus(determineKpiStatus(snapshot.getValue(), kpiDef));
                    return snapshot;
                })
                .collect(Collectors.toList());

        var saved = snapshots.stream()
                .map(kpiSnapshotRepository::save)
                .collect(Collectors.toList());

        return saved.stream()
                .map(KpiSnapshotDto::from)
                .collect(Collectors.toList());
    }

    private Double calculateKpiValue(KpiDefinition kpiDef, UUID projectId) {
        try {
            String source = kpiDef.getModuleSource() != null ? kpiDef.getModuleSource().toUpperCase() : "";
            String code = kpiDef.getCode() != null ? kpiDef.getCode().toUpperCase() : "";

            return switch (source) {
                case "EVM" -> calculateEvmKpi(code, projectId);
                case "SCHEDULE" -> calculateScheduleKpi(code, projectId);
                case "COST" -> calculateCostKpi(code, projectId);
                case "RISK" -> calculateRiskKpi(code, projectId);
                default -> calculateGenericKpi(code, projectId);
            };
        } catch (Exception e) {
            log.warn("Failed to calculate KPI {} for project {}: {}", kpiDef.getCode(), projectId, e.getMessage());
            return 0.0;
        }
    }

    private Double calculateEvmKpi(String code, UUID projectId) {
        String column = switch (code) {
            case "SPI" -> "schedule_performance_index";
            case "CPI" -> "cost_performance_index";
            case "EAC" -> "estimate_at_completion";
            case "ETC" -> "estimate_to_complete";
            case "VAC" -> "variance_at_completion";
            default -> null;
        };
        if (column == null) return 0.0;

        Object result = em.createNativeQuery(
                "SELECT " + column + " FROM evm.evm_calculations " +
                "WHERE project_id = ?1 ORDER BY data_date DESC LIMIT 1")
            .setParameter(1, projectId)
            .getSingleResult();
        return result != null ? ((Number) result).doubleValue() : 0.0;
    }

    private Double calculateScheduleKpi(String code, UUID projectId) {
        return switch (code) {
            case "PCT_COMPLETE" -> {
                Object result = em.createNativeQuery(
                        "SELECT CASE WHEN COUNT(*) > 0 THEN " +
                        "ROUND((100.0 * COUNT(CASE WHEN status = 'COMPLETED' THEN 1 END) / COUNT(*))::numeric, 2) " +
                        "ELSE 0 END FROM activity.activities WHERE project_id = ?1")
                    .setParameter(1, projectId)
                    .getSingleResult();
                yield result != null ? ((Number) result).doubleValue() : 0.0;
            }
            case "CRITICAL_PATH_LENGTH" -> {
                Object result = em.createNativeQuery(
                        "SELECT COALESCE(SUM(remaining_duration), 0) FROM activity.activities " +
                        "WHERE project_id = ?1 AND is_critical = true")
                    .setParameter(1, projectId)
                    .getSingleResult();
                yield result != null ? ((Number) result).doubleValue() : 0.0;
            }
            default -> 0.0;
        };
    }

    private Double calculateCostKpi(String code, UUID projectId) {
        return switch (code) {
            case "BUDGET_UTILIZATION" -> {
                Object result = em.createNativeQuery(
                        "SELECT CASE WHEN SUM(budgeted_cost) > 0 THEN " +
                        "ROUND((100.0 * SUM(actual_cost) / SUM(budgeted_cost))::numeric, 2) " +
                        "ELSE 0 END FROM cost.activity_expenses WHERE project_id = ?1")
                    .setParameter(1, projectId)
                    .getSingleResult();
                yield result != null ? ((Number) result).doubleValue() : 0.0;
            }
            default -> 0.0;
        };
    }

    private Double calculateRiskKpi(String code, UUID projectId) {
        return switch (code) {
            case "RISK_EXPOSURE" -> {
                Object result = em.createNativeQuery(
                        "SELECT COALESCE(AVG(risk_score), 0) FROM risk.risks " +
                        "WHERE project_id = ?1 AND status IN ('OPEN', 'ACTIVE')")
                    .setParameter(1, projectId)
                    .getSingleResult();
                yield result != null ? ((Number) result).doubleValue() : 0.0;
            }
            default -> 0.0;
        };
    }

    private Double calculateGenericKpi(String code, UUID projectId) {
        // Fallback: try to derive from common KPI codes
        return switch (code) {
            case "SPI", "CPI" -> calculateEvmKpi(code, projectId);
            case "PCT_COMPLETE" -> calculateScheduleKpi(code, projectId);
            case "BUDGET_UTILIZATION" -> calculateCostKpi(code, projectId);
            default -> 0.0;
        };
    }

    private KpiSnapshot.KpiStatus determineKpiStatus(Double value, KpiDefinition kpiDef) {
        if (value == null) {
            return KpiSnapshot.KpiStatus.RED;
        }

        if (kpiDef.getGreenThreshold() != null && value >= kpiDef.getGreenThreshold()) {
            return KpiSnapshot.KpiStatus.GREEN;
        }

        if (kpiDef.getAmberThreshold() != null && value >= kpiDef.getAmberThreshold()) {
            return KpiSnapshot.KpiStatus.AMBER;
        }

        return KpiSnapshot.KpiStatus.RED;
    }
}
