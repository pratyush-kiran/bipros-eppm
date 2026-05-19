package com.bipros.project.application.util;

import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprManpower;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit/cost formula coverage for {@link DprCostFormulas}.
 *
 * <p><b>Rule pinned by these tests:</b> {@code cost = nos × rate}; {@code units = nos}.
 * The {@code basis} argument and the row's {@code workingHours} / {@code otHours} fields
 * are logging-only and must never enter cost or unit math. Mirrors the canonical Resource
 * Plan formula in {@code ResourceAssignmentCostRollupListener}.
 */
@DisplayName("DprCostFormulas")
class DprCostFormulasTest {

  @Nested
  @DisplayName("manpowerUnits")
  class ManpowerUnits {

    @Test
    @DisplayName("DAY basis: returns nos (hours informational)")
    void dayBasisReturnsNos() {
      DprManpower row = DprManpower.builder()
          .nos(2).workingHours(new BigDecimal("8")).otHours(BigDecimal.ZERO).build();
      assertThat(DprCostFormulas.manpowerUnits(row, "DAY"))
          .isEqualByComparingTo(new BigDecimal("2"));
    }

    @Test
    @DisplayName("HOUR basis: returns nos — hours never enter unit count")
    void hourBasisStillReturnsNos() {
      DprManpower row = DprManpower.builder()
          .nos(2).workingHours(new BigDecimal("8")).otHours(new BigDecimal("2")).build();
      assertThat(DprCostFormulas.manpowerUnits(row, "HOUR"))
          .isEqualByComparingTo(new BigDecimal("2"));
    }

    @Test
    @DisplayName("EACH / unknown basis: returns nos")
    void eachBasisReturnsNos() {
      DprManpower row = DprManpower.builder()
          .nos(3).workingHours(new BigDecimal("8")).build();
      assertThat(DprCostFormulas.manpowerUnits(row, "EACH"))
          .isEqualByComparingTo(new BigDecimal("3"));
    }

    @Test
    @DisplayName("null / zero nos: returns ZERO")
    void zeroNosReturnsZero() {
      DprManpower row = DprManpower.builder().nos(0).build();
      assertThat(DprCostFormulas.manpowerUnits(row, "DAY"))
          .isEqualByComparingTo(BigDecimal.ZERO);
    }
  }

  @Nested
  @DisplayName("manpowerLineCost")
  class ManpowerCost {

    @Test
    @DisplayName("DAY basis: rate × nos (Mason 2 × ₹1000 = ₹2000)")
    void dayBasisCost() {
      DprManpower row = DprManpower.builder()
          .nos(2).workingHours(new BigDecimal("8")).build();
      assertThat(DprCostFormulas.manpowerLineCost(row, new BigDecimal("1000"), "DAY"))
          .isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    @Test
    @DisplayName("HOUR basis: rate × nos — hours never multiplied in")
    void hourBasisCostStillNosTimesRate() {
      DprManpower row = DprManpower.builder()
          .nos(2).workingHours(new BigDecimal("8")).otHours(new BigDecimal("2")).build();
      // Was 2200 (rate × nos × (hrs + ot × 1.5)). New rule: rate × nos = 200.
      assertThat(DprCostFormulas.manpowerLineCost(row, new BigDecimal("100"), "HOUR"))
          .isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    @DisplayName("null rate or zero nos: returns null")
    void nullRateReturnsNull() {
      DprManpower row = DprManpower.builder().nos(2).build();
      assertThat(DprCostFormulas.manpowerLineCost(row, null, "DAY")).isNull();
    }
  }

  @Nested
  @DisplayName("equipmentUnits")
  class EquipmentUnits {

    @Test
    @DisplayName("HOUR basis: returns nos — regression guard against the Crane / hours-multiplier bug")
    void hourBasisReturnsNosNotNosTimesHours() {
      DprEquipment row = DprEquipment.builder()
          .nos(2).workingHours(new BigDecimal("8")).build();
      assertThat(DprCostFormulas.equipmentUnits(row, "HOUR"))
          .isEqualByComparingTo(new BigDecimal("2"));
    }

    @Test
    @DisplayName("DAY basis: returns nos")
    void dayBasisReturnsNos() {
      DprEquipment row = DprEquipment.builder()
          .nos(2).workingHours(new BigDecimal("8")).build();
      assertThat(DprCostFormulas.equipmentUnits(row, "DAY"))
          .isEqualByComparingTo(new BigDecimal("2"));
    }
  }

  @Nested
  @DisplayName("equipmentLineCost")
  class EquipmentCost {

    @Test
    @DisplayName("HOUR basis: rate × nos — hours stay informational (Excavator 2 × ₹3000 = ₹6000)")
    void hourBasisCostStillNosTimesRate() {
      DprEquipment row = DprEquipment.builder()
          .nos(2).workingHours(new BigDecimal("8"))
          .idleHours(new BigDecimal("2")).breakdownHours(new BigDecimal("1")).build();
      assertThat(DprCostFormulas.equipmentLineCost(row, new BigDecimal("3000"), "HOUR"))
          .isEqualByComparingTo(new BigDecimal("6000.00"));
    }

    @Test
    @DisplayName("DAY basis: rate × nos")
    void dayBasisCost() {
      DprEquipment row = DprEquipment.builder()
          .nos(2).workingHours(new BigDecimal("8")).build();
      assertThat(DprCostFormulas.equipmentLineCost(row, new BigDecimal("500"), "DAY"))
          .isEqualByComparingTo(new BigDecimal("1000.00"));
    }
  }
}
