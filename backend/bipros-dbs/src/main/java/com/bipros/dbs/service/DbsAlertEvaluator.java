package com.bipros.dbs.service;

import com.bipros.dbs.domain.model.DbsDailyEngineer;
import com.bipros.dbs.domain.model.DbsDailyProject;
import com.bipros.dbs.domain.model.DbsDailySupervisor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates "soft" health-check alerts against a DBS daily row. Pure / stateless —
 * the caller fetches the aggregate (or builds a zero-filled stub) and passes it in.
 *
 * <p>Alert codes:
 * <ul>
 *   <li>{@code LOW_CONTRIBUTION_PCT} — contribution% &lt; 5% (and income &gt; 0).</li>
 *   <li>{@code NEGATIVE_CONTRIBUTION} — contribution &lt; 0.</li>
 *   <li>{@code RUNAWAY_FUEL} — fuel &gt; 50% of total expense (and expense &gt; 0).</li>
 *   <li>{@code MISSING_RATE_DATA} — manpower &amp; machinery both zero yet income &gt; 0
 *       (i.e. work happened but rate-master resolution returned nothing).</li>
 * </ul>
 */
@Component
public class DbsAlertEvaluator {

    public static final String LOW_CONTRIBUTION_PCT = "LOW_CONTRIBUTION_PCT";
    public static final String NEGATIVE_CONTRIBUTION = "NEGATIVE_CONTRIBUTION";
    public static final String RUNAWAY_FUEL = "RUNAWAY_FUEL";
    public static final String MISSING_RATE_DATA = "MISSING_RATE_DATA";

    private static final BigDecimal LOW_CONTRIBUTION_THRESHOLD = new BigDecimal("0.05");
    private static final BigDecimal RUNAWAY_FUEL_RATIO = new BigDecimal("0.5");

    public List<String> evaluate(DbsDailyProject row) {
        if (row == null) return List.of();
        return evaluateCore(
            row.getContributionPct(),
            row.getContribution(),
            row.getFuelAmount(),
            row.getTotalExpense(),
            row.getManpowerAmount(),
            row.getMachineryAmount(),
            row.getTotalIncome()
        );
    }

    public List<String> evaluate(DbsDailySupervisor row) {
        if (row == null) return List.of();
        return evaluateCore(
            row.getContributionPct(),
            row.getContribution(),
            row.getFuelAmount(),
            row.getTotalExpense(),
            row.getManpowerAmount(),
            row.getMachineryAmount(),
            row.getTotalIncome()
        );
    }

    public List<String> evaluate(DbsDailyEngineer row) {
        if (row == null) return List.of();
        return evaluateCore(
            row.getContributionPct(),
            row.getContribution(),
            row.getFuelAmount(),
            row.getTotalExpense(),
            row.getManpowerAmount(),
            row.getMachineryAmount(),
            row.getTotalIncome()
        );
    }

    private List<String> evaluateCore(BigDecimal contributionPct,
                                       BigDecimal contribution,
                                       BigDecimal fuelAmount,
                                       BigDecimal totalExpense,
                                       BigDecimal manpowerAmount,
                                       BigDecimal machineryAmount,
                                       BigDecimal totalIncome) {
        BigDecimal pct = nz(contributionPct);
        BigDecimal contrib = nz(contribution);
        BigDecimal fuel = nz(fuelAmount);
        BigDecimal expense = nz(totalExpense);
        BigDecimal manpower = nz(manpowerAmount);
        BigDecimal machinery = nz(machineryAmount);
        BigDecimal income = nz(totalIncome);

        List<String> alerts = new ArrayList<>();

        if (contrib.signum() < 0) {
            alerts.add(NEGATIVE_CONTRIBUTION);
        }

        if (income.signum() > 0 && pct.compareTo(LOW_CONTRIBUTION_THRESHOLD) < 0 && contrib.signum() >= 0) {
            alerts.add(LOW_CONTRIBUTION_PCT);
        }

        if (expense.signum() > 0
            && fuel.compareTo(expense.multiply(RUNAWAY_FUEL_RATIO)) > 0) {
            alerts.add(RUNAWAY_FUEL);
        }

        if (manpower.signum() == 0
            && machinery.signum() == 0
            && income.signum() > 0) {
            alerts.add(MISSING_RATE_DATA);
        }

        return alerts;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
