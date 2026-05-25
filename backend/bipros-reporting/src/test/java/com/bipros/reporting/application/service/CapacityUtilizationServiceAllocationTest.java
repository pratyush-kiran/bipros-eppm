package com.bipros.reporting.application.service;

import com.bipros.reporting.application.dto.CapacityUtilizationReport;
import com.bipros.reporting.application.dto.CapacityUtilizationReport.HiddenSideNote;
import com.bipros.reporting.application.dto.CapacityUtilizationReport.RolePeriod;
import com.bipros.reporting.application.dto.CapacityUtilizationReport.RoleRow;
import com.bipros.reporting.application.dto.CapacityUtilizationReport.Section;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Service-level integration smoke test for the per-DPR allocation refactor.
 *
 * <p>Exercises Scenario A from the design spec end-to-end:
 * <pre>
 *   DPR on 2026-05-22 for "Unclassified structural excavation"
 *     Workdone: 540 Nos
 *     Manpower: 3 Helper + 1 Foreman + 1 Supervisor   (norm 23.53 → side expected 117.65)
 *     Equipment: 4 Excavator + 1 each Tipper/Dozer/Dumper/Wheel Loader/Hand drilling
 *                                                    (norm 20.4  → side expected 183.60)
 *     norm_combination = SERIES → manpower wins (smaller expected gets full qty)
 * </pre>
 *
 * <p>Expected outcome the test pins:
 * <ul>
 *   <li>Manpower section: Helper qty 324, Foreman qty 108, Supervisor qty 108 (cumulative bucket).</li>
 *   <li>Manpower budgets: Helper ≈ 13.77 day, Foreman ≈ 4.59, Supervisor ≈ 4.59; util ≈ 459 % each.</li>
 *   <li>Equipment section: hiddenSideNotes carries one entry for the activity with governingSide=MANPOWER, mode=SERIES.</li>
 * </ul>
 *
 * <p>This is a Mockito-based test (not @DataJpaTest) because:
 * <ol>
 *   <li>The rest of the reporting module's tests use Mockito (see ScheduleVarianceReportServiceTest);
 *       @DataJpaTest isn't a pattern that exists anywhere in the module.</li>
 *   <li>The service's native SQL joins across three schemas (project.dpr_manpower,
 *       resource.productivity_norms, activity.activities) which H2 doesn't model.</li>
 *   <li>buildSection is private and there's no public helper that takes a Contribution list, so
 *       a pure unit angle isn't reachable without an invasive refactor.</li>
 * </ol>
 * The EntityManager is mocked and the native queries are routed by SQL-fragment matching.
 * Day/Month bucket reference dates depend on {@code LocalDate.now()}, so the test asserts on
 * the {@code cumulative} bucket which is deterministic for an explicit {@code [from, to]} window.
 */
@DisplayName("CapacityUtilizationReportService — per-DPR allocation (Scenario A)")
class CapacityUtilizationServiceAllocationTest {

  // Fixed identities so we can assert by role.
  private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ACTIVITY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID WORK_ACTIVITY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID DPR_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

  private static final UUID HELPER_ROLE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID FOREMAN_ROLE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID SUPER_ROLE = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  private static final UUID EXCAVATOR_ROLE = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
  private static final UUID TIPPER_ROLE = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
  private static final UUID DOZER_ROLE = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
  private static final UUID DUMPER_ROLE = UUID.fromString("12121212-1212-1212-1212-121212121212");
  private static final UUID WHEEL_LOADER_ROLE = UUID.fromString("34343434-3434-3434-3434-343434343434");
  private static final UUID HAND_DRILL_ROLE = UUID.fromString("56565656-5656-5656-5656-565656565656");

  private static final LocalDate REPORT_DATE = LocalDate.of(2026, 5, 22);
  private static final BigDecimal QTY_DONE = new BigDecimal("540");
  private static final BigDecimal MP_NORM = new BigDecimal("23.53");   // Nos / man-day
  private static final BigDecimal EQ_NORM = new BigDecimal("20.4");    // Nos / day

  private EntityManager em;
  private CapacityUtilizationReportService service;

  @BeforeEach
  void setUp() throws Exception {
    em = mock(EntityManager.class);
    service = new CapacityUtilizationReportService();
    // The service uses field injection via @PersistenceContext; set it directly.
    Field f = CapacityUtilizationReportService.class.getDeclaredField("em");
    f.setAccessible(true);
    f.set(service, em);

    wireQueries();
  }

  @Test
  @DisplayName("SERIES, manpower wins: 540 → Helper 324, Foreman 108, Supervisor 108; equipment side hidden")
  void scenarioA_seriesManpowerWinsAndEquipmentHidden() {
    CapacityUtilizationReport report = service.build(
        PROJECT_ID, REPORT_DATE, REPORT_DATE, "ROLE", null);

    // ── Manpower section: three roles, allocated qty per the allocator's NOS-weighted split.
    Section mp = report.manpower();
    assertThat(mp).isNotNull();
    assertThat(mp.rows()).hasSize(3);

    RoleRow helper = findRow(mp.rows(), HELPER_ROLE);
    RoleRow foreman = findRow(mp.rows(), FOREMAN_ROLE);
    RoleRow supervisor = findRow(mp.rows(), SUPER_ROLE);

    // Cumulative qty: 540 × 3/5 = 324, 540 × 1/5 = 108 each.
    assertThat(helper.cumulative().qty()).isEqualByComparingTo(new BigDecimal("324.0000"));
    assertThat(foreman.cumulative().qty()).isEqualByComparingTo(new BigDecimal("108.0000"));
    assertThat(supervisor.cumulative().qty()).isEqualByComparingTo(new BigDecimal("108.0000"));

    // Budget days = allocated qty / norm. 324 / 23.53 ≈ 13.77; 108 / 23.53 ≈ 4.59.
    assertThat(helper.cumulative().budgetDays())
        .isCloseTo(new BigDecimal("13.77"), within2dp());
    assertThat(foreman.cumulative().budgetDays())
        .isCloseTo(new BigDecimal("4.59"), within2dp());
    assertThat(supervisor.cumulative().budgetDays())
        .isCloseTo(new BigDecimal("4.59"), within2dp());

    // Actual days = NOS (1 day on this DPR): Helper 3, Foreman 1, Supervisor 1.
    assertThat(helper.cumulative().actualDays()).isEqualByComparingTo(new BigDecimal("3"));
    assertThat(foreman.cumulative().actualDays()).isEqualByComparingTo(new BigDecimal("1"));
    assertThat(supervisor.cumulative().actualDays()).isEqualByComparingTo(new BigDecimal("1"));

    // Util % = budgetDays / actualDays × 100 ≈ 459 %.
    assertThat(helper.cumulative().utilizationPct())
        .isCloseTo(new BigDecimal("459.00"), within(new BigDecimal("1")));
    assertThat(foreman.cumulative().utilizationPct())
        .isCloseTo(new BigDecimal("459.00"), within(new BigDecimal("1")));
    assertThat(supervisor.cumulative().utilizationPct())
        .isCloseTo(new BigDecimal("459.00"), within(new BigDecimal("1")));

    // ── Equipment section: side hidden by allocator → one HiddenSideNote, no qty credited.
    Section eq = report.equipment();
    assertThat(eq).isNotNull();
    assertThat(eq.hiddenSideNotes())
        .extracting(HiddenSideNote::activityId, HiddenSideNote::governingSide, HiddenSideNote::mode)
        .containsExactly(tuple(ACTIVITY_ID, "MANPOWER", "SERIES"));

    // Equipment rows still appear (so headcount is preserved) but with no allocated qty.
    assertThat(eq.rows()).isNotEmpty();
    for (RoleRow r : eq.rows()) {
      // Cumulative qty must be zero (or null) — no qty was credited on the hidden side.
      BigDecimal q = r.cumulative().qty();
      assertThat(q == null || q.signum() == 0)
          .as("equipment role %s should have no cumulative qty on hidden side", r.roleId())
          .isTrue();
    }
  }

  // ─── Mock wiring ───────────────────────────────────────────────────────────────────────────

  /**
   * Route {@code em.createNativeQuery(sql)} by SQL fragment to a {@link Query} mock that returns
   * the canned result list. setParameter and setMaxResults return the same mock so chained calls
   * compile and execute through the mock without NPE.
   */
  private void wireQueries() {
    when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      Query q = mock(Query.class);
      when(q.setParameter(anyString(), any())).thenReturn(q);
      when(q.setParameter(any(Integer.class), any())).thenReturn(q);
      when(q.setMaxResults(anyInt())).thenReturn(q);

      List<?> result = canResultFor(sql);
      when(q.getResultList()).thenReturn(result);
      return q;
    });
  }

  /** Map SQL fragment → canned result list. Order matters — the more specific tests come first. */
  private List<?> canResultFor(String sql) {
    // 1. Manpower contributions — 12-column Object[] per (role, dpr) row.
    if (sql.contains("FROM project.dpr_manpower m ")) {
      return List.of(
          mpRow(HELPER_ROLE, "HELPER", "Helper", 3),
          mpRow(FOREMAN_ROLE, "FOREMAN", "Foreman", 1),
          mpRow(SUPER_ROLE, "SUPER", "Supervisor", 1));
    }
    // 2. Equipment contributions — same shape.
    if (sql.contains("FROM project.dpr_equipment e ")) {
      return List.of(
          eqRow(EXCAVATOR_ROLE, "EXCAVATOR", "Excavator", 4),
          eqRow(TIPPER_ROLE, "TIPPER", "Tipper", 1),
          eqRow(DOZER_ROLE, "DOZER", "Dozer", 1),
          eqRow(DUMPER_ROLE, "DUMPER", "Dumper", 1),
          eqRow(WHEEL_LOADER_ROLE, "WHEEL_LOADER", "Wheel Loader", 1),
          eqRow(HAND_DRILL_ROLE, "HAND_DRILL", "Hand drilling", 1));
    }
    // 3. Other-side expected per (DPR, activity).
    //    When the service is building MANPOWER, it asks for the EQUIPMENT side expected (= 9 × 20.4).
    //    When building EQUIPMENT, it asks for the MANPOWER side expected (= 5 × 23.53).
    //    The SQL is parameterised on :nt; we can't see the bound value here, so route both by which
    //    underlying table the query reads from (dpr_equipment ↔ "other side is equipment").
    if (sql.contains("SUM(r.nos * ") && sql.contains("FROM project.dpr_equipment r ")) {
      // Other-side = EQUIPMENT (we're inside MANPOWER buildSection).
      // Note: List.<Object[]>of(arr) — without the type witness List.of(arr) treats arr as varargs.
      return List.<Object[]>of(new Object[] { DPR_ID, ACTIVITY_ID, new BigDecimal("183.60") });
    }
    if (sql.contains("SUM(r.nos * ") && sql.contains("FROM project.dpr_manpower r ")) {
      // Other-side = MANPOWER (we're inside EQUIPMENT buildSection).
      return List.<Object[]>of(new Object[] { DPR_ID, ACTIVITY_ID, new BigDecimal("117.65") });
    }
    // 4. Norm combination for an activity.
    if (sql.contains("COALESCE(wa.norm_combination, 'SERIES')")) {
      return List.of((Object) "SERIES");
    }
    // 5. Productivity norm resolution (resolveNorm). Differentiate manpower vs equipment by which
    //    output column the SELECT picked. Both return the unscoped norm because we don't stub a
    //    role-level row — the service then falls back to the unscoped query (same SQL shape with
    //    different params), and that's what we answer here.
    if (sql.contains("FROM resource.productivity_norms n") && sql.contains("output_per_man_per_day")) {
      // Manpower norm lookup (preferredColumn = COALESCE(output_per_man_per_day, output_per_day)).
      return List.of((Object) MP_NORM);
    }
    if (sql.contains("FROM resource.productivity_norms n") && sql.contains("n.output_per_day")) {
      // Equipment norm lookup (preferredColumn = n.output_per_day).
      return List.of((Object) EQ_NORM);
    }
    // 6. Rate lookups — return empty so loadRoleRates yields no rate (cost stays null, util still computed).
    if (sql.contains("FROM project.dpr_manpower dr ") || sql.contains("FROM project.dpr_equipment dr ")) {
      return List.of();
    }
    if (sql.contains("FROM resource.manpower_role_rates")
        || sql.contains("FROM resource.equipment_role_variants")) {
      return List.of();
    }
    // 7. Planned headcount (resource_assignments) — empty, planned columns stay null.
    if (sql.contains("FROM resource.resource_assignments ra ")) {
      return List.of();
    }
    // 8. Activities-with-DPR per bucket — empty (test doesn't rely on planned-from-DPR fallback).
    if (sql.contains("FROM project.daily_progress_reports")
        && sql.contains("DISTINCT activity_id")) {
      return List.of();
    }
    // Fallback — any unrecognised SQL just returns empty.
    return List.of();
  }

  // ─── Row builders for the contribution result-set shape (12 columns) ──────────────────────

  /** Column order matches mapContributions(): role_id, role_code, role_name, dpr_id, report_date,
   *  work_activity_id, wa_name, wa_default_unit, qty_executed, role_days, trade, activity_id. */
  private Object[] mpRow(UUID roleId, String code, String name, int nos) {
    return contribRow(roleId, code, name, "Helper/Foreman/Super", nos);
  }

  private Object[] eqRow(UUID roleId, String code, String name, int nos) {
    return contribRow(roleId, code, name, "Equipment", nos);
  }

  private Object[] contribRow(UUID roleId, String code, String name, String trade, int nos) {
    return new Object[] {
        roleId,
        code,
        name,
        DPR_ID,
        Date.valueOf(REPORT_DATE),
        WORK_ACTIVITY_ID,
        "Unclassified structural excavation",
        "Nos",
        QTY_DONE,                       // qty_executed
        new BigDecimal(nos),            // role_days = Σ nos
        trade,
        ACTIVITY_ID
    };
  }

  private static RoleRow findRow(List<RoleRow> rows, UUID roleId) {
    return rows.stream().filter(r -> roleId.equals(r.roleId())).findFirst()
        .orElseThrow(() -> new AssertionError("no row for role " + roleId));
  }

  private static org.assertj.core.data.Offset<BigDecimal> within2dp() {
    return org.assertj.core.data.Offset.offset(new BigDecimal("0.01"));
  }

  private static org.assertj.core.data.Offset<BigDecimal> within(BigDecimal eps) {
    return org.assertj.core.data.Offset.offset(eps);
  }

  // Suppress unused warning for the list builder helpers.
  @SuppressWarnings("unused")
  private static <T> List<T> mutableList(List<T> in) {
    return new ArrayList<>(in);
  }
}
