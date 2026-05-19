package com.bipros.dbs.service.calculator;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Phase 7 + cumulative-scope fix: verifies {@link SectionFBoqCalculator}
 * <ol>
 *   <li>buckets each BOQ line into {@code directBoqAmount} / {@code prelimBoqAmount}
 *       according to the activity's {@code is_preliminary} flag, AND</li>
 *   <li>only populates cumulative {@code plannedAmount} / {@code achievedAmount} in
 *       project scope ({@code supervisorUserId == null}). In supervisor scope it
 *       returns zero, so the per-tier rollups do not double-count when summed.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SectionFBoqCalculatorPrelimSplitTest {

    @Mock private EntityManager em;
    @Mock private Query nativeQuery;

    @Test
    @DisplayName("supervisor scope: forTheDay split is computed but cumulative planned/achieved are zero")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void compute_supervisorScope_zerosCumulative() throws Exception {
        SectionFBoqCalculator calc = new SectionFBoqCalculator();
        injectEntityManager(calc, em);

        UUID projectId = UUID.randomUUID();
        UUID supId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 18);

        UUID boq1 = UUID.randomUUID();
        UUID boq2 = UUID.randomUUID();

        Object[] direct = new Object[]{
            "BOQ-001", "Earthwork in cutting", "cum",
            new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("50000"),
            new BigDecimal("100"), boq1, Boolean.FALSE
        };
        Object[] prelim = new Object[]{
            "BOQ-100", "Mobilisation", "LS",
            new BigDecimal("250"), new BigDecimal("1"), new BigDecimal("5000"),
            new BigDecimal("2"), boq2, Boolean.TRUE
        };

        when(em.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.getResultList()).thenReturn((List) List.of(direct, prelim));

        BoqSectionResult result = calc.compute(projectId, supId, date);

        // For-the-day math still runs per supervisor — these stay correct.
        assertThat(result.forTheDayAmount()).isEqualByComparingTo("1250.00");
        assertThat(result.directBoqAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.prelimBoqAmount()).isEqualByComparingTo("250.00");
        assertThat(result.directBoqAmount().add(result.prelimBoqAmount()))
            .isEqualByComparingTo(result.forTheDayAmount());

        // Cumulative figures must be zero in supervisor scope so the engineer/CM/PM
        // rollups don't double-count when two supervisors share a BOQ item.
        assertThat(result.plannedAmount()).isEqualByComparingTo("0");
        assertThat(result.achievedAmount()).isEqualByComparingTo("0");

        // The flat line list always carries every DPR row (no de-dupe).
        assertThat(result.lines()).hasSize(2);
    }

    @Test
    @DisplayName("project scope (null supervisor): cumulative planned/achieved are computed and deduped")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void compute_projectScope_populatesCumulative() throws Exception {
        SectionFBoqCalculator calc = new SectionFBoqCalculator();
        injectEntityManager(calc, em);

        UUID projectId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 18);

        UUID boq1 = UUID.randomUUID();
        UUID boq2 = UUID.randomUUID();

        Object[] direct = new Object[]{
            "BOQ-001", "Earthwork in cutting", "cum",
            new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("50000"),
            new BigDecimal("100"), boq1, Boolean.FALSE
        };
        Object[] prelim = new Object[]{
            "BOQ-100", "Mobilisation", "LS",
            new BigDecimal("250"), new BigDecimal("1"), new BigDecimal("5000"),
            new BigDecimal("2"), boq2, Boolean.TRUE
        };

        when(em.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.getResultList()).thenReturn((List) List.of(direct, prelim));

        BoqSectionResult result = calc.compute(projectId, null, date);

        // Project scope must populate cumulative — these are the values the PM rollup
        // historically used. planned dedupes by boq_id; achieved = qty_to_date × rate.
        assertThat(result.plannedAmount()).isEqualByComparingTo("55000.00");
        assertThat(result.achievedAmount()).isEqualByComparingTo("10500.00");
    }

    @Test
    @DisplayName("compute returns empty result when EM throws — falls back gracefully")
    void compute_emptyOnException() {
        SectionFBoqCalculator calc = new SectionFBoqCalculator();
        // No EntityManager wired — NPE inside compute -> caught -> empty.
        BoqSectionResult result = calc.compute(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now());
        assertThat(result.forTheDayAmount()).isEqualByComparingTo("0");
        assertThat(result.directBoqAmount()).isEqualByComparingTo("0");
        assertThat(result.prelimBoqAmount()).isEqualByComparingTo("0");
        assertThat(result.lines()).isEmpty();
    }

    private static void injectEntityManager(SectionFBoqCalculator target, EntityManager em) throws Exception {
        // SectionFBoqCalculator declares `private EntityManager em` annotated with
        // @PersistenceContext. In a unit test we reach in via reflection so we don't have
        // to bring up a Spring container just to assert the split arithmetic.
        Field f = SectionFBoqCalculator.class.getDeclaredField("em");
        f.setAccessible(true);
        f.set(target, em);
    }
}
