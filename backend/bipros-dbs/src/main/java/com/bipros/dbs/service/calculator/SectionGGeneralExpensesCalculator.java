package com.bipros.dbs.service.calculator;

import com.bipros.dbs.overhead.domain.model.GeneralExpenseMonthlyEntry;
import com.bipros.dbs.overhead.domain.model.GeneralExpensePlanItem;
import com.bipros.dbs.overhead.domain.repository.GeneralExpenseMonthlyEntryRepository;
import com.bipros.dbs.overhead.domain.repository.GeneralExpensePlanItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Section G — General Expenses (monthly overheads). For a given {@code reportDate},
 * looks up every {@link GeneralExpenseMonthlyEntry} whose {@code yearMonth} matches
 * the date's month, divides each amount by the number of days in that month, and
 * emits one {@link SectionLine} per plan item. The aggregated {@code totalAmount}
 * is the daily-prorated overhead — daily view and month-period view both reconcile
 * to the same monthly total.
 *
 * <p>PM tier only: the daily-project recompute invokes this calculator;
 * supervisor/engineer/CM tiers do not carry general-expense columns.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SectionGGeneralExpensesCalculator {

    private final GeneralExpensePlanItemRepository planRepo;
    private final GeneralExpenseMonthlyEntryRepository entryRepo;

    public Result compute(UUID projectId, LocalDate date) {
        YearMonth ym = YearMonth.from(date);
        int yearMonthKey = ym.getYear() * 100 + ym.getMonthValue();
        int daysInMonth = ym.lengthOfMonth();

        List<GeneralExpenseMonthlyEntry> entries =
            entryRepo.findByProjectIdAndYearMonth(projectId, yearMonthKey);
        if (entries.isEmpty()) {
            return new Result(BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }
        Map<UUID, GeneralExpensePlanItem> planByItemId = planRepo
            .findByProjectIdOrderBySortOrderAsc(projectId).stream()
            .collect(Collectors.toMap(GeneralExpensePlanItem::getId, p -> p));

        List<SectionLine> lines = new ArrayList<>();
        BigDecimal monthTotal = BigDecimal.ZERO;
        for (GeneralExpenseMonthlyEntry e : entries) {
            GeneralExpensePlanItem plan = planByItemId.get(e.getPlanItemId());
            if (plan == null) continue;
            BigDecimal monthlyAmount = e.getAchievedAmount() == null ? BigDecimal.ZERO : e.getAchievedAmount();
            monthTotal = monthTotal.add(monthlyAmount);
            BigDecimal dailyAmount = monthlyAmount
                .divide(BigDecimal.valueOf(daysInMonth), 2, RoundingMode.HALF_UP);
            String unit = plan.getUnit() == null ? "Month" : plan.getUnit().name();
            lines.add(new SectionLine(plan.getDescription(), unit,
                plan.getRate(), e.getAchievedQty(), dailyAmount));
        }
        BigDecimal dailyTotal = monthTotal
            .divide(BigDecimal.valueOf(daysInMonth), 2, RoundingMode.HALF_UP);
        return new Result(dailyTotal, monthTotal.setScale(2, RoundingMode.HALF_UP), lines);
    }

    /**
     * Triple of (daily-prorated amount used in the DBS row, raw month total for
     * audit/reference, per-item lines for the UI accordion).
     */
    public record Result(BigDecimal dailyAmount, BigDecimal monthlyTotal, List<SectionLine> lines) {}
}
