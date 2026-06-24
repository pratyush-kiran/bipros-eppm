package com.bipros.dbs.service.calculator;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class SectionDFuelCalculatorTest {

    private final SectionDFuelCalculator calc = new SectionDFuelCalculator();
    private static final BigDecimal R = new BigDecimal("0.35");

    @Test
    void fuelIs35PercentOfMachinery() {
        SectionResult r = calc.fromMachinery(new BigDecimal("52.00"), R);
        assertThat(r.totalAmount()).isEqualByComparingTo("18.20");
        assertThat(r.lines()).hasSize(1);
        SectionLine line = r.lines().get(0);
        assertThat(line.description()).contains("35").contains("machinery");
        assertThat(line.rate()).isEqualByComparingTo("0.35");
        assertThat(line.quantity()).isEqualByComparingTo("52.00");
        assertThat(line.totalAmount()).isEqualByComparingTo("18.20");
    }

    @Test
    void roundsToTwoDecimalsHalfUp() {
        // 10.01 × 0.35 = 3.5035 → 3.50
        assertThat(calc.fromMachinery(new BigDecimal("10.01"), R).totalAmount())
            .isEqualByComparingTo("3.50");
    }

    @Test
    void zeroMachineryReturnsEmpty() {
        SectionResult r = calc.fromMachinery(BigDecimal.ZERO, R);
        assertThat(r.totalAmount()).isEqualByComparingTo("0");
        assertThat(r.lines()).isEmpty();
    }

    @Test
    void nullMachineryReturnsEmpty() {
        assertThat(calc.fromMachinery(null, R).lines()).isEmpty();
    }

    @Test
    void zeroOrNullRatioReturnsEmpty() {
        assertThat(calc.fromMachinery(new BigDecimal("52.00"), BigDecimal.ZERO).lines()).isEmpty();
        assertThat(calc.fromMachinery(new BigDecimal("52.00"), null).lines()).isEmpty();
    }
}
