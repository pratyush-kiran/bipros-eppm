package com.bipros.project.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Math contract for the DPR productivity preview. Pins the rule that NEITHER manpower NOR
 * equipment expected output uses hours-per-day scaling — HRS is a logging-only field on the DPR.
 * The helpers don't even ACCEPT hours as parameters; this test class re-asserts the per-day-NOS
 * formula so the structural decision is locked in.
 *
 * <p>Equipment expected = outputPerDay × NOS. Manpower expected = outputPerManPerDay × NOS.
 */
@DisplayName("DprProductivityPreviewService — math (HRS not used)")
class DprProductivityPreviewServiceTest {

  @Nested
  @DisplayName("equipment expected (per-day NOS basis, no HRS)")
  class EquipmentExpected {

    @Test
    @DisplayName("9 units × 20.4/day → 183.60")
    void perDayBasis() {
      BigDecimal expected = DprProductivityPreviewService.computeEquipmentExpected(
          new BigDecimal("20.4"), 9);
      assertThat(expected).isEqualByComparingTo(new BigDecimal("183.60"));
    }

    @Test
    @DisplayName("single unit × outputPerDay → outputPerDay")
    void singleUnit() {
      BigDecimal expected = DprProductivityPreviewService.computeEquipmentExpected(
          new BigDecimal("100"), 1);
      assertThat(expected).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("null outputPerDay → ZERO (guard)")
    void nullNormReturnsZero() {
      BigDecimal expected = DprProductivityPreviewService.computeEquipmentExpected(null, 4);
      assertThat(expected).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("zero nos → ZERO (guard)")
    void zeroNosReturnsZero() {
      BigDecimal expected = DprProductivityPreviewService.computeEquipmentExpected(
          new BigDecimal("50"), 0);
      assertThat(expected).isEqualByComparingTo(BigDecimal.ZERO);
    }
  }

  @Nested
  @DisplayName("manpower expected (per-man-per-day NOS basis)")
  class ManpowerExpected {

    @Test
    @DisplayName("5 men × 23.53/man-day → 117.65")
    void simpleSum() {
      BigDecimal expected = DprProductivityPreviewService.computeManpowerExpected(
          new BigDecimal("23.53"), 5);
      assertThat(expected).isEqualByComparingTo(new BigDecimal("117.65"));
    }

    @Test
    @DisplayName("null outputPerManPerDay → ZERO (guard)")
    void nullNormReturnsZero() {
      BigDecimal expected = DprProductivityPreviewService.computeManpowerExpected(null, 5);
      assertThat(expected).isEqualByComparingTo(BigDecimal.ZERO);
    }
  }
}
