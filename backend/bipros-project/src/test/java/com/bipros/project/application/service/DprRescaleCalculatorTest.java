package com.bipros.project.application.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class DprRescaleCalculatorTest {

  @Test
  void targetNos_landsEfficiencyInBand() {
    // qty 200, norm 100/day => budgetDays 2. Deployed nos should give eff in [0.85,1.05].
    UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    int nos = DprRescaleCalculator.targetNos(new BigDecimal("200"), new BigDecimal("100"), id);
    double eff = 2.0 / nos;          // budgetDays / countedDays
    assertThat(eff).isBetween(0.85, 1.05);
    assertThat(nos).isGreaterThanOrEqualTo(1);
  }

  @Test
  void targetNos_isDeterministicPerDpr() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    int a = DprRescaleCalculator.targetNos(new BigDecimal("500"), new BigDecimal("70"), id);
    int b = DprRescaleCalculator.targetNos(new BigDecimal("500"), new BigDecimal("70"), id);
    assertThat(a).isEqualTo(b);
  }

  @Test
  void targetNos_zeroWhenNoNormOrNoWork() {
    UUID id = UUID.randomUUID();
    assertThat(DprRescaleCalculator.targetNos(new BigDecimal("100"), BigDecimal.ZERO, id)).isZero();
    assertThat(DprRescaleCalculator.targetNos(BigDecimal.ZERO, new BigDecimal("100"), id)).isZero();
  }

  @Test
  void distribute_preservesRatioAndTotal() {
    List<Integer> out = DprRescaleCalculator.distribute(10, List.of(1, 1, 3)); // ratio 1:1:3
    assertThat(out).hasSize(3);
    assertThat(out.stream().mapToInt(Integer::intValue).sum()).isEqualTo(10);
    assertThat(out.get(2)).isGreaterThan(out.get(0)); // the 3-weight row gets more
  }

  @Test
  void distribute_evenWhenAllZero() {
    List<Integer> out = DprRescaleCalculator.distribute(9, List.of(0, 0, 0));
    assertThat(out.stream().mapToInt(Integer::intValue).sum()).isEqualTo(9);
    assertThat(out).containsExactly(3, 3, 3);
  }
}
