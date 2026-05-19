package com.bipros.cost.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure helpers for P&L / margin math shared by {@link BudgetedMarginService} and
 * {@link BoqMarginService}. Revenue is always {@code rate × qty}; margin is
 * {@code revenue − actualCost}; margin% is {@code margin / revenue} expressed as 0..1
 * (the frontend renders as percent).
 */
public final class MarginCalculator {

    private static final int AMOUNT_SCALE = 2;
    private static final int RATIO_SCALE = 6;

    private MarginCalculator() {}

    public record MarginResult(BigDecimal revenue, BigDecimal actualCost,
                               BigDecimal margin, BigDecimal marginPct) {
        public static MarginResult zero() {
            return new MarginResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
        }
    }

    public static MarginResult compute(BigDecimal rate, BigDecimal qty, BigDecimal actualCost) {
        BigDecimal r = nz(rate);
        BigDecimal q = nz(qty);
        BigDecimal revenue = r.multiply(q).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        BigDecimal cost = nz(actualCost).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        BigDecimal margin = revenue.subtract(cost);
        BigDecimal pct = revenue.signum() == 0
            ? null
            : margin.divide(revenue, RATIO_SCALE, RoundingMode.HALF_UP);
        return new MarginResult(revenue, cost, margin, pct);
    }

    public static MarginResult fromRevenueAndCost(BigDecimal revenue, BigDecimal actualCost) {
        BigDecimal rev = nz(revenue).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        BigDecimal cost = nz(actualCost).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        BigDecimal margin = rev.subtract(cost);
        BigDecimal pct = rev.signum() == 0
            ? null
            : margin.divide(rev, RATIO_SCALE, RoundingMode.HALF_UP);
        return new MarginResult(rev, cost, margin, pct);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
