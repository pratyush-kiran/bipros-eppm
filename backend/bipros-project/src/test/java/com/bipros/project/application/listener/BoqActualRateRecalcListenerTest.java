package com.bipros.project.application.listener;

import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.repository.BoqItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link BoqActualRateRecalcListener}. The listener issues two native SQL queries
 * per recompute (qty + cost). We mock the {@link EntityManager} so that:
 * <ul>
 *   <li>the qty query returns a fixed positive denominator,</li>
 *   <li>the cost query returns a fixed numerator that includes the simulated sub-contractor cost,</li>
 *   <li>and we capture every SQL string passed to {@code createNativeQuery} so we can assert the
 *       new sub-contractor UNION branch is present in the cost SQL.</li>
 * </ul>
 *
 * <p>This double-checks the fix: <em>both</em> the SQL contains the new branch <em>and</em> the
 * resulting {@code actualRate} / {@code actualAmount} on the BOQ item reflect the contribution.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BoqActualRateRecalcListener — sub-contractor cost rollup")
class BoqActualRateRecalcListenerTest {

  @Mock private BoqItemRepository boqItemRepository;
  @Mock private EntityManager em;

  private BoqActualRateRecalcListener listener;

  private final UUID projectId = UUID.randomUUID();
  private final UUID boqItemId = UUID.randomUUID();

  /** Every SQL string the listener hands to {@code em.createNativeQuery}, captured in order. */
  private final List<String> capturedSql = new ArrayList<>();

  /** Per-call single-result values keyed by SQL substring. */
  private BigDecimal qtyResult;
  private BigDecimal costResult;

  @BeforeEach
  void setUp() {
    listener = new BoqActualRateRecalcListener(boqItemRepository);
    ReflectionTestUtils.setField(listener, "em", em);

    lenient().when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      capturedSql.add(sql);
      Query q = org.mockito.Mockito.mock(Query.class);
      lenient().when(q.setParameter(anyString(), any())).thenReturn(q);
      lenient().when(q.getSingleResult()).thenAnswer(x -> {
        // The qty query selects SUM(qty_executed); the cost query selects SUM(u.contrib).
        if (sql.contains("SUM(qty_executed)")) return qtyResult;
        if (sql.contains("SUM(u.contrib)")) return costResult;
        return BigDecimal.ZERO;
      });
      return q;
    });
  }

  @Test
  @DisplayName("sumQtyExecuted SQL filters to APPROVED DPRs only")
  void sumQtyExecutedSqlFiltersApproved() {
    BoqItem item = boqItem();
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.of(item));
    qtyResult = new BigDecimal("100");
    costResult = new BigDecimal("100000");

    listener.onDprSubmitted(event());

    boolean qtyQueryFiltersApproved = capturedSql.stream().anyMatch(sql ->
        sql.contains("SUM(qty_executed)")
            && sql.contains("approval_status = 'APPROVED'"));
    assertThat(qtyQueryFiltersApproved)
        .as("qty SQL must restrict to approval_status = 'APPROVED'")
        .isTrue();
  }

  @Test
  @DisplayName("sumActualCost SQL filters every DPR-joined branch to APPROVED")
  void sumActualCostSqlFiltersApproved() {
    BoqItem item = boqItem();
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.of(item));
    qtyResult = new BigDecimal("100");
    costResult = new BigDecimal("100000");

    listener.onDprSubmitted(event());

    String costSql = capturedSql.stream()
        .filter(sql -> sql.contains("SUM(u.contrib)"))
        .findFirst()
        .orElse("");
    // Every DPR-joined branch (manpower, equipment, material, sub-contractor, MCL subquery)
    // must restrict to approved DPRs.
    assertThat(costSql)
        .as("cost SQL must contain approval_status = 'APPROVED' filter")
        .contains("approval_status = 'APPROVED'");
    // Specifically, the MCL subquery's inner DPR filter must also be approved-only.
    assertThat(costSql)
        .as("MCL subquery must also filter DPRs to APPROVED")
        .contains("material_consumption_logs")
        .contains("d2.approval_status = 'APPROVED'");
  }

  @Test
  @DisplayName("sumActualCost SQL contains the sub-contractor UNION branch")
  void sumActualCostSqlIncludesSubContractorBranch() {
    BoqItem item = boqItem();
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.of(item));
    qtyResult = new BigDecimal("100");
    costResult = new BigDecimal("127500");   // 30 × 4250 from the SC branch

    listener.onDprSubmitted(event());

    // At least one captured SQL string must be the cost SUM containing the SC join.
    boolean foundScBranch = capturedSql.stream().anyMatch(sql ->
        sql.contains("SUM(u.contrib)")
            && sql.contains("project.dpr_sub_contractor")
            && sql.contains("resource.activity_sub_contractor_assignments")
            && sql.contains("activity_sub_contractor_assignment_id")
            && sql.contains("COALESCE(a.rate_per_unit, 0)"));
    assertThat(foundScBranch)
        .as("cost SQL must include the dpr_sub_contractor × rate_per_unit UNION branch")
        .isTrue();
  }

  @Test
  @DisplayName("recompute saves actualRate = cost / qty when cost includes sub-contractor contribution")
  void recomputeUsesSubContractorBackedCost() {
    BoqItem item = boqItem();
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.of(item));
    qtyResult = new BigDecimal("100");
    costResult = new BigDecimal("127500");   // pretend only contributor is one SC row 30 × 4250

    listener.onDprSubmitted(event());

    ArgumentCaptor<BoqItem> saved = ArgumentCaptor.forClass(BoqItem.class);
    verify(boqItemRepository).save(saved.capture());
    BoqItem out = saved.getValue();
    // 127500 / 100 = 1275 (scale 4 from the listener).
    assertThat(out.getActualRate()).isEqualByComparingTo("1275.0000");
    // BoqCalculator.recompute sets actualAmount = qtyExecutedToDate × actualRate, scale 2.
    // qtyExecutedToDate on the item is 100 (see fixture); 100 × 1275 = 127500.00.
    assertThat(out.getActualAmount()).isEqualByComparingTo("127500.00");
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private BoqItem boqItem() {
    BoqItem item = new BoqItem();
    item.setId(boqItemId);
    item.setProjectId(projectId);
    item.setBoqQty(new BigDecimal("200"));
    item.setBoqRate(new BigDecimal("4000"));
    item.setBudgetedRate(new BigDecimal("4000"));
    item.setQtyExecutedToDate(new BigDecimal("100"));
    item.setManualOverride(Boolean.FALSE);
    return item;
  }

  private DprSubmittedEvent event() {
    return DprSubmittedEvent.withoutChildren(
        projectId, UUID.randomUUID(), java.time.LocalDate.of(2026, 5, 22),
        "Test Activity", null, new BigDecimal("100"), null, null,
        DprMutationType.CREATED, UUID.randomUUID(), boqItemId, boqItemId);
  }
}
