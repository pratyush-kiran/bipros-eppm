package com.bipros.api.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.admin.domain.model.Organisation;
import com.bipros.admin.domain.repository.OrganisationRepository;
import com.bipros.api.dto.WorkPackageRowResponse;
import com.bipros.evm.application.dto.WbsEvmNode;
import com.bipros.evm.application.service.EvmRollupService;
import com.bipros.evm.domain.entity.EtcMethod;
import com.bipros.evm.domain.entity.EvmTechnique;
import com.bipros.project.application.dto.WbsNodeResponse;
import com.bipros.project.application.service.WbsService;
import com.bipros.project.domain.model.WbsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkPackageRollupService")
class WorkPackageRollupServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 23);
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID WBS_PARENT_ID = UUID.randomUUID();
    private static final UUID WP_A_ID = UUID.randomUUID();
    private static final UUID WP_B_ID = UUID.randomUUID();
    private static final UUID WP_EMPTY_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();

    @Mock private WbsService wbsService;
    @Mock private ActivityRepository activityRepository;
    @Mock private EvmRollupService evmRollupService;
    @Mock private OrganisationRepository organisationRepository;

    private Clock fixedClock;
    private WorkPackageRollupService service;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        service = new WorkPackageRollupService(
            wbsService, activityRepository, evmRollupService, organisationRepository, fixedClock);
    }

    @Test
    @DisplayName("returns empty list when project has no work packages")
    void emptyWhenNoWorkPackages() {
        when(wbsService.getTree(PROJECT_ID)).thenReturn(List.of());

        List<WorkPackageRowResponse> rows = service.listWorkPackages(PROJECT_ID);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("weighted % complete uses duration as weight")
    void weightedPercentComplete() {
        // Two activities under WP_A: 10d at 100%, 30d at 0% → weighted = (10*100 + 30*0)/40 = 25
        stubTree(workPackage(WP_A_ID, "WP-A", "Earthworks"));
        stubActivities(
            activity(WP_A_ID, 10.0, 100.0, ActivityStatus.COMPLETED, TODAY.minusDays(20), TODAY.minusDays(10), null),
            activity(WP_A_ID, 30.0, 0.0, ActivityStatus.NOT_STARTED, TODAY.plusDays(5), TODAY.plusDays(35), null));
        stubNoEvm();

        WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

        assertThat(row.weightedPercentComplete()).isEqualTo(25.0, offset(0.0001));
        assertThat(row.activityCountTotal()).isEqualTo(2L);
        assertThat(row.activityCountDone()).isEqualTo(1L);
        assertThat(row.activityCountNotStarted()).isEqualTo(1L);
    }

    @Test
    @DisplayName("falls back to simple average when no durations are set")
    void simpleAverageWhenNoDurations() {
        stubTree(workPackage(WP_A_ID, "WP-A", "Earthworks"));
        stubActivities(
            activity(WP_A_ID, null, 40.0, ActivityStatus.IN_PROGRESS, null, null, null),
            activity(WP_A_ID, null, 80.0, ActivityStatus.IN_PROGRESS, null, null, null));
        stubNoEvm();

        WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

        assertThat(row.weightedPercentComplete()).isEqualTo(60.0, offset(0.0001));
    }

    @Nested
    @DisplayName("days-behind derivation")
    class DaysBehind {

        @Test
        @DisplayName("complete work package → 0 days behind")
        void completeIsZero() {
            stubTree(workPackage(WP_A_ID, "WP-A", "Done"));
            stubActivities(
                activity(WP_A_ID, 10.0, 100.0, ActivityStatus.COMPLETED,
                    TODAY.minusDays(20), TODAY.minusDays(10), null));
            stubNoEvm();

            WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

            assertThat(row.daysBehindSchedule()).isEqualTo(0L);
            assertThat(row.derivedStatus()).isEqualTo("DONE");
        }

        @Test
        @DisplayName("past planned finish and incomplete → calendar days late")
        void overdueIncomplete() {
            stubTree(workPackage(WP_A_ID, "WP-A", "Late"));
            stubActivities(
                activity(WP_A_ID, 10.0, 60.0, ActivityStatus.IN_PROGRESS,
                    TODAY.minusDays(20), TODAY.minusDays(5), null));
            stubNoEvm();

            WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

            assertThat(row.daysBehindSchedule()).isEqualTo(5L);
            assertThat(row.derivedStatus()).isEqualTo("DELAYED");
        }

        @Test
        @DisplayName("in-flight with progress gap → gap-derived days")
        void inFlightWithGap() {
            // Activity planned 100d, today is 50% through (50d in). Progress = 25%. Gap = 25% × 100 = 25 days.
            stubTree(workPackage(WP_A_ID, "WP-A", "In flight"));
            stubActivities(
                activity(WP_A_ID, 100.0, 25.0, ActivityStatus.IN_PROGRESS,
                    TODAY.minusDays(50), TODAY.plusDays(50), null));
            stubNoEvm();

            WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

            assertThat(row.daysBehindSchedule()).isEqualTo(25L);
        }

        @Test
        @DisplayName("in-flight and ahead of plan → 0 days behind")
        void inFlightAhead() {
            // 100d duration, 50% time elapsed, 75% actual → ahead → 0.
            stubTree(workPackage(WP_A_ID, "WP-A", "Ahead"));
            stubActivities(
                activity(WP_A_ID, 100.0, 75.0, ActivityStatus.IN_PROGRESS,
                    TODAY.minusDays(50), TODAY.plusDays(50), null));
            stubNoEvm();

            WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

            assertThat(row.daysBehindSchedule()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("status derivation")
    class Status {

        @Test
        @DisplayName("no activities → PLANNED")
        void noActivities() {
            stubTree(workPackage(WP_EMPTY_ID, "WP-E", "Future"));
            when(activityRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
            stubNoEvm();

            WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

            assertThat(row.derivedStatus()).isEqualTo("PLANNED");
            assertThat(row.activityCountTotal()).isZero();
        }

        @Test
        @DisplayName("SPI below 0.9 → AT_RISK")
        void spiAtRisk() {
            stubTree(workPackage(WP_A_ID, "WP-A", "At risk"));
            stubActivities(
                activity(WP_A_ID, 10.0, 40.0, ActivityStatus.IN_PROGRESS,
                    TODAY.minusDays(2), TODAY.plusDays(8), null));
            when(evmRollupService.calculateWbsTree(eq(PROJECT_ID), any(), any()))
                .thenReturn(List.of(evmLeaf(WP_A_ID, 0.75, 0.95)));
            stubNoOrgs();

            WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

            assertThat(row.spi()).isEqualTo(0.75);
            assertThat(row.derivedStatus()).isEqualTo("AT_RISK");
        }

        @Test
        @DisplayName("NOT_STARTED before its planned start")
        void notStartedBeforePlannedStart() {
            stubTree(workPackage(WP_A_ID, "WP-A", "Future"));
            stubActivities(
                activity(WP_A_ID, 10.0, 0.0, ActivityStatus.NOT_STARTED,
                    TODAY.plusDays(10), TODAY.plusDays(20), null));
            stubNoEvm();

            WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

            assertThat(row.derivedStatus()).isEqualTo("NOT_STARTED");
        }
    }

    @Test
    @DisplayName("min total float drives onCriticalPath flag")
    void criticalPath() {
        stubTree(workPackage(WP_A_ID, "WP-A", "Crit"));
        stubActivities(
            activity(WP_A_ID, 10.0, 40.0, ActivityStatus.IN_PROGRESS,
                TODAY.minusDays(2), TODAY.plusDays(8), 5.0),
            activity(WP_A_ID, 10.0, 0.0, ActivityStatus.NOT_STARTED,
                TODAY.plusDays(8), TODAY.plusDays(18), 0.0));
        stubNoEvm();

        WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

        assertThat(row.minTotalFloat()).isEqualTo(0.0);
        assertThat(row.onCriticalPath()).isTrue();
    }

    @Test
    @DisplayName("EVM passthroughs join by wbsNodeId")
    void evmPassthrough() {
        stubTree(workPackage(WP_A_ID, "WP-A", "Has EVM"));
        stubActivities(
            activity(WP_A_ID, 10.0, 50.0, ActivityStatus.IN_PROGRESS,
                TODAY.minusDays(5), TODAY.plusDays(5), null));
        when(evmRollupService.calculateWbsTree(eq(PROJECT_ID),
            eq(EvmTechnique.ACTIVITY_PERCENT_COMPLETE), eq(EtcMethod.CPI_BASED)))
            .thenReturn(List.of(evmLeaf(WP_A_ID, 1.05, 1.10)));
        stubNoOrgs();

        WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

        assertThat(row.spi()).isEqualTo(1.05);
        assertThat(row.cpi()).isEqualTo(1.10);
        assertThat(row.bac()).isEqualByComparingTo("100.00");
        assertThat(row.earnedValue()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("missing EVM row leaves passthrough fields null")
    void missingEvm() {
        stubTree(workPackage(WP_A_ID, "WP-A", "No EVM"));
        stubActivities(
            activity(WP_A_ID, 10.0, 50.0, ActivityStatus.IN_PROGRESS,
                TODAY.minusDays(5), TODAY.plusDays(5), null));
        stubNoEvm();

        WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

        assertThat(row.spi()).isNull();
        assertThat(row.cpi()).isNull();
        assertThat(row.bac()).isNull();
    }

    @Test
    @DisplayName("contractor name resolves from responsibleOrganisationId")
    void contractorLookup() {
        WbsNodeResponse wp = new WbsNodeResponse(
            WP_A_ID, "WP-A", "With contractor", WBS_PARENT_ID, PROJECT_ID, null, 0,
            null, null, 3, WbsType.WORK_PACKAGE, null, null,
            ORG_ID, TODAY, TODAY.plusDays(30), new BigDecimal("4.2"),
            null, null, null, null, List.of());
        WbsNodeResponse parent = new WbsNodeResponse(
            WBS_PARENT_ID, "P", "Parent", null, PROJECT_ID, null, 0,
            null, null, 1, WbsType.NODE, null, null, null, null, null, null,
            null, null, null, null, List.of(wp));
        when(wbsService.getTree(PROJECT_ID)).thenReturn(List.of(parent));

        stubActivities(
            activity(WP_A_ID, 10.0, 40.0, ActivityStatus.IN_PROGRESS,
                TODAY.minusDays(2), TODAY.plusDays(8), null));
        stubNoEvm();
        Organisation org = new Organisation();
        org.setId(ORG_ID);
        org.setName("Patel Infra Ltd");
        when(organisationRepository.findAllById(any())).thenReturn(List.of(org));

        WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

        assertThat(row.contractorOrganisationId()).isEqualTo(ORG_ID);
        assertThat(row.contractorName()).isEqualTo("Patel Infra Ltd");
        assertThat(row.parentName()).isEqualTo("Parent");
    }

    @Test
    @DisplayName("falls back to leaf nodes when no WORK_PACKAGE-typed nodes exist")
    void fallbackToLeafs() {
        WbsNodeResponse leaf = new WbsNodeResponse(
            WP_A_ID, "L-1", "Leaf", WBS_PARENT_ID, PROJECT_ID, null, 0,
            null, null, 2, WbsType.PACKAGE, null, null, null,
            TODAY.minusDays(2), TODAY.plusDays(8), null, null, null, null, null, List.of());
        WbsNodeResponse parent = new WbsNodeResponse(
            WBS_PARENT_ID, "P", "Parent", null, PROJECT_ID, null, 0,
            null, null, 1, WbsType.NODE, null, null, null, null, null, null,
            null, null, null, null, List.of(leaf));
        when(wbsService.getTree(PROJECT_ID)).thenReturn(List.of(parent));

        stubActivities(
            activity(WP_A_ID, 10.0, 50.0, ActivityStatus.IN_PROGRESS,
                TODAY.minusDays(2), TODAY.plusDays(8), null));
        stubNoEvm();

        List<WorkPackageRowResponse> rows = service.listWorkPackages(PROJECT_ID);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).code()).isEqualTo("L-1");
    }

    @Test
    @DisplayName("delayed activity count counts past-finish incomplete activities")
    void delayedCount() {
        stubTree(workPackage(WP_A_ID, "WP-A", "Mixed"));
        stubActivities(
            activity(WP_A_ID, 10.0, 100.0, ActivityStatus.COMPLETED,
                TODAY.minusDays(30), TODAY.minusDays(20), null),
            activity(WP_A_ID, 10.0, 50.0, ActivityStatus.IN_PROGRESS,
                TODAY.minusDays(20), TODAY.minusDays(5), null),
            activity(WP_A_ID, 10.0, 30.0, ActivityStatus.IN_PROGRESS,
                TODAY.minusDays(20), TODAY.minusDays(3), null),
            activity(WP_A_ID, 10.0, 0.0, ActivityStatus.NOT_STARTED,
                TODAY.plusDays(2), TODAY.plusDays(12), null));
        stubNoEvm();

        WorkPackageRowResponse row = service.listWorkPackages(PROJECT_ID).get(0);

        assertThat(row.activityCountDone()).isEqualTo(1L);
        assertThat(row.activityCountInProgress()).isEqualTo(2L);
        assertThat(row.activityCountNotStarted()).isEqualTo(1L);
        assertThat(row.activityCountDelayed()).isEqualTo(2L);
    }

    // ---- helpers ----------------------------------------------------------

    private void stubTree(WbsNodeResponse... workPackages) {
        WbsNodeResponse parent = new WbsNodeResponse(
            WBS_PARENT_ID, "P-1", "Parent", null, PROJECT_ID, null, 0,
            null, null, 1, WbsType.NODE, null, null, null, null, null, null,
            null, null, null, null, List.of(workPackages));
        when(wbsService.getTree(PROJECT_ID)).thenReturn(List.of(parent));
    }

    private void stubActivities(Activity... acts) {
        when(activityRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(acts));
    }

    private void stubNoEvm() {
        when(evmRollupService.calculateWbsTree(eq(PROJECT_ID), any(), any())).thenReturn(List.of());
    }

    private void stubNoOrgs() {
        // organisationRepository.findAllById is never reached when no responsibleOrganisationId
        // is set on the WBS rows, so no stub is needed.
    }

    private static WbsNodeResponse workPackage(UUID id, String code, String name) {
        return new WbsNodeResponse(
            id, code, name, WBS_PARENT_ID, PROJECT_ID, null, 0,
            null, null, 3, WbsType.WORK_PACKAGE, null, null, null, null, null, null,
            null, null, null, null, List.of());
    }

    private static Activity activity(UUID wbsNodeId, Double duration, Double pct, ActivityStatus status,
                                     LocalDate plannedStart, LocalDate plannedFinish, Double totalFloat) {
        Activity a = new Activity();
        a.setId(UUID.randomUUID());
        a.setProjectId(PROJECT_ID);
        a.setWbsNodeId(wbsNodeId);
        a.setOriginalDuration(duration);
        a.setPercentComplete(pct);
        a.setStatus(status);
        a.setPlannedStartDate(plannedStart);
        a.setPlannedFinishDate(plannedFinish);
        a.setTotalFloat(totalFloat);
        return a;
    }

    private static WbsEvmNode evmLeaf(UUID id, double spi, double cpi) {
        return new WbsEvmNode(
            id, "WP", "code",
            new BigDecimal("100.00"),   // BAC
            new BigDecimal("60.00"),    // PV
            new BigDecimal("50.00"),    // EV
            new BigDecimal("45.00"),    // AC
            new BigDecimal("-10.00"),   // SV
            new BigDecimal("5.00"),     // CV
            spi,
            cpi,
            new BigDecimal("90.00"),    // EAC
            new BigDecimal("0.00"),     // ETC
            new BigDecimal("10.00"),    // VAC
            List.of());
    }
}
