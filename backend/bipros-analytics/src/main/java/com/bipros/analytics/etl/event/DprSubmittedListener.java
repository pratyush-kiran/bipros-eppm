package com.bipros.analytics.etl.event;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.analytics.etl.AnalyticsEtlService;
import com.bipros.analytics.etl.DeadLetterHandler;
import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.DprMaterial;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class DprSubmittedListener {

    // Control tokens used by common LLM tokenizers — strip before storing to prevent injection via remarks
    private static final Pattern CONTROL_TOKEN_PATTERN = Pattern.compile(
            "<\\|im_start\\|>|<\\|im_end\\|>|<\\|endoftext\\|>|<\\|fim_prefix\\|>|<\\|fim_middle\\|>|<\\|fim_suffix\\|>|<\\|fim_pad\\|>|<\\|startoftext\\|>",
            Pattern.CASE_INSENSITIVE);

    private static String sanitizeRemarks(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String stripped = CONTROL_TOKEN_PATTERN.matcher(raw).replaceAll("");
        return "<UNTRUSTED_DATA>" + stripped + "</UNTRUSTED_DATA>";
    }

    private final AnalyticsEtlService etl;
    private final DeadLetterHandler deadLetter;
    private final DailyProgressReportRepository dprRepository;
    private final DprManpowerRepository manpowerRepository;
    private final DprEquipmentRepository equipmentRepository;
    private final DprMaterialRepository materialRepository;
    private final ActivityRepository activityRepository;
    private final MeterRegistry meterRegistry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDprSubmitted(DprSubmittedEvent event) {
        try {
            // KNOWN LIMITATION (matches existing fact_dpr_logs convention):
            // - On DELETED we don't write any superseding row — historical fact rows linger
            //   until the nightly reaper sweeps them. Same applies to UPDATE-with-fewer-children:
            //   orphaned fact_dpr_manpower_daily / _equipment / _material rows for removed
            //   manpower_row_ids stay visible to FINAL queries because ReplacingMergeTree only
            //   dedupes within identical (project_id, dpr_id, row_id) tuples. Mitigation: the
            //   AI tools should join against the OLTP store for authoritative existence; a
            //   future tombstone column or ALTER TABLE ... DELETE mutation would close the gap.
            if (event.eventType() == DprMutationType.DELETED) {
                log.debug("Skipping ETL for deleted DPR: project={} dpr={}", event.projectId(), event.dprId());
                return;
            }

            DailyProgressReport dpr = dprRepository.findById(event.dprId()).orElse(null);
            if (dpr == null) {
                log.warn("DPR not found for event: {}", event);
                return;
            }

            UUID activityId = resolveActivityId(event.projectId(), dpr.getActivityName());

            BigDecimal cumulative = dprRepository.sumQtyExecutedThroughDate(
                    event.projectId(), dpr.getActivityName(), dpr.getReportDate());
            Double cumulativeDouble = cumulative != null ? cumulative.doubleValue() : null;

            // Phase 4.3: feed ETL with supervisor USER id (FK to public.users.id) instead of the
            // legacy supervisor RESOURCE id. Liquibase 091 drops daily_progress_reports.supervisor_resource_id;
            // the canonical identity is now supervisor_user_id (added by 087). Prefer the event's payload
            // when present (CREATED/UPDATED), fall back to the freshly-loaded DPR for older callers.
            UUID supervisorUserId = event.supervisorUserId() != null
                    ? event.supervisorUserId()
                    : dpr.getSupervisorUserId();
            etl.insertDprLog(
                    event.projectId(), activityId, dpr.getId(), dpr.getReportDate(),
                    supervisorUserId,
                    dpr.getSupervisorName(),
                    dpr.getChainageFromM() != null ? dpr.getChainageFromM().doubleValue() : null,
                    dpr.getChainageToM() != null ? dpr.getChainageToM().doubleValue() : null,
                    event.qtyExecuted() != null ? event.qtyExecuted().doubleValue() : null,
                    cumulativeDouble,
                    dpr.getWeatherCondition(),
                    null,
                    sanitizeRemarks(dpr.getRemarks()));

            etl.insertActivityProgressDaily(
                    event.projectId(), activityId, dpr.getReportDate(),
                    null, null,
                    event.qtyExecuted() != null ? event.qtyExecuted().doubleValue() : null,
                    cumulativeDouble,
                    dpr.getChainageFromM() != null ? dpr.getChainageFromM().doubleValue() : null,
                    dpr.getChainageToM() != null ? dpr.getChainageToM().doubleValue() : null,
                    "dpr");

            // Per-resource child rows. Skip the fetch when the event reports zero counts; on
            // CREATE/UPDATE the publisher reliably populates these. Double-fetch defensively
            // when the event was published by older code (counts == 0 but rows might exist).
            if (event.manpowerCount() > 0 || event.equipmentCount() > 0 || event.materialCount() > 0
                    || event.eventType() == DprMutationType.UPDATED) {
                streamManpower(event.projectId(), activityId, dpr);
                streamEquipment(event.projectId(), activityId, dpr);
                streamMaterial(event.projectId(), activityId, dpr);
            }

            log.debug("ETL processed DprSubmittedEvent: project={} dpr={} type={} mp={} eq={} mat={}",
                    event.projectId(), event.dprId(), event.eventType(),
                    event.manpowerCount(), event.equipmentCount(), event.materialCount());
        } catch (Exception e) {
            log.error("ETL failed for DprSubmittedEvent: {}", event, e);
            meterRegistry.counter("bipros.analytics.etl.failures", "fact", "fact_dpr_logs").increment();
            deadLetter.record("project.daily_progress_reports", "fact_dpr_logs", event, e);
        }
    }

    private void streamManpower(UUID projectId, UUID activityId, DailyProgressReport dpr) {
        List<DprManpower> rows = manpowerRepository.findByDprIdOrderByTradeAsc(dpr.getId());
        for (DprManpower r : rows) {
            etl.insertDprManpowerDaily(
                    projectId, activityId, dpr.getId(), r.getId(), dpr.getReportDate(),
                    r.getTrade(),
                    r.getCategory() != null ? r.getCategory().name() : null,
                    r.getContractorName(),
                    r.getNos(),
                    toDouble(r.getWorkingHours()),
                    toDouble(r.getOtHours()));
        }
    }

    private void streamEquipment(UUID projectId, UUID activityId, DailyProgressReport dpr) {
        List<DprEquipment> rows = equipmentRepository.findByDprIdOrderByEquipmentTypeAsc(dpr.getId());
        for (DprEquipment r : rows) {
            etl.insertDprEquipmentDaily(
                    projectId, activityId, dpr.getId(), r.getId(), dpr.getReportDate(),
                    r.getEquipmentType(),
                    r.getFleetNo(),
                    r.getOwnership() != null ? r.getOwnership().name() : null,
                    r.getNos(),
                    toDouble(r.getWorkingHours()),
                    toDouble(r.getIdleHours()),
                    toDouble(r.getBreakdownHours()),
                    toDouble(r.getFuelLitres()),
                    r.getOperatorName(),
                    r.getAvailabilityStatus() != null ? r.getAvailabilityStatus().name() : null);
        }
    }

    private void streamMaterial(UUID projectId, UUID activityId, DailyProgressReport dpr) {
        List<DprMaterial> rows = materialRepository.findByDprIdOrderByMaterialNameAsc(dpr.getId());
        for (DprMaterial r : rows) {
            etl.insertDprMaterialDaily(
                    projectId, activityId, dpr.getId(), r.getId(), dpr.getReportDate(),
                    r.getMaterialName(),
                    r.getUnit(),
                    toDouble(r.getQuantity()),
                    r.getSource(),
                    r.getVendorName(),
                    r.getBatchNo());
        }
    }

    private static Double toDouble(BigDecimal v) {
        return v != null ? v.doubleValue() : null;
    }

    private UUID resolveActivityId(UUID projectId, String activityName) {
        try {
            List<Activity> activities = activityRepository.findByProjectId(projectId);
            return activities.stream()
                    .filter(a -> a.getName() != null && a.getName().equalsIgnoreCase(activityName))
                    .findFirst()
                    .map(Activity::getId)
                    .orElse(new UUID(0L, 0L));
        } catch (Exception e) {
            return new UUID(0L, 0L);
        }
    }
}
