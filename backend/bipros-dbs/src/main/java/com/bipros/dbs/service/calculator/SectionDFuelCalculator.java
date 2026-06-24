package com.bipros.dbs.service.calculator;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Section D — Fuel. Derived value: {@code fuel = ratio × Section C (Machinery) cost}.
 *
 * <p>The ratio (default 0.35 = 35%) is supplied by the caller from
 * {@code bipros.dbs.fuel-machinery-cost-ratio}. Replaces the former litre×rate /
 * diesel-material logic (the {@code project_costing_config.fuel_rate_per_litre} table was
 * never created, so that path always returned zero). Diesel/fuel material rows now count
 * only under Section E (Material).
 */
@Component
public class SectionDFuelCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * @param machineryTotal Section C total for the same (project, scope, date)
     * @param ratio          decimal fraction, e.g. 0.35 for 35%
     * @return a single derived line, or {@link SectionResult#empty()} when there is nothing to charge
     */
    public SectionResult fromMachinery(BigDecimal machineryTotal, BigDecimal ratio) {
        if (machineryTotal == null || machineryTotal.signum() == 0
                || ratio == null || ratio.signum() == 0) {
            return SectionResult.empty();
        }
        BigDecimal amount = machineryTotal.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        String pct = ratio.multiply(HUNDRED).stripTrailingZeros().toPlainString();
        SectionLine line = new SectionLine(
            "Fuel — " + pct + "% of machinery", "%", ratio, machineryTotal, amount);
        return new SectionResult(amount, List.of(line));
    }
}
