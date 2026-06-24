package com.bipros.activity.application.percent;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.PercentCompleteType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PercentCompleteCalculator")
class PercentCompleteCalculatorTest {

  private final PercentCompleteCalculator calculator = new PercentCompleteCalculator();

  @Nested
  @DisplayName("PHYSICAL")
  class Physical {

    @Test
    @DisplayName("uses physicalPercentComplete when set")
    void usesPhysicalPercentComplete() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.PHYSICAL);
      activity.setPhysicalPercentComplete(42.0);
      activity.setPercentComplete(10.0);

      PercentCompleteCalculator.Result result = calculator.calculate(activity, null, null, LocalDate.now());

      assertEquals(42.0, result.percent());
      assertEquals(ActivityStatus.IN_PROGRESS, result.status());
      assertNull(result.forcedActualFinish());
    }

    @Test
    @DisplayName("falls back to percentComplete when physical is null")
    void fallsBackToPercentComplete() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.PHYSICAL);
      activity.setPhysicalPercentComplete(null);
      activity.setPercentComplete(55.0);

      PercentCompleteCalculator.Result result = calculator.calculate(activity, null, null, LocalDate.now());

      assertEquals(55.0, result.percent());
    }

    @Test
    @DisplayName("clamps values to [0, 100]")
    void clampsRange() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.PHYSICAL);
      activity.setPhysicalPercentComplete(150.0);

      PercentCompleteCalculator.Result result = calculator.calculate(activity, null, null, LocalDate.now());

      assertEquals(100.0, result.percent());
    }

    @Test
    @DisplayName("rounds to 2 decimal places")
    void roundsTo2dp() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.PHYSICAL);
      activity.setPhysicalPercentComplete(33.336);

      PercentCompleteCalculator.Result result = calculator.calculate(activity, null, null, LocalDate.now());

      assertEquals(33.34, result.percent());
    }
  }

  @Nested
  @DisplayName("UNITS")
  class Units {

    @Test
    @DisplayName("computes actual / planned * 100")
    void computesUnitsPercent() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.UNITS);
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, 100.0, 60.0, LocalDate.now());

      assertEquals(60.0, result.percent());
      assertEquals(ActivityStatus.IN_PROGRESS, result.status());
    }

    @Test
    @DisplayName("caps at 100%")
    void capsAt100() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.UNITS);
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, 100.0, 138.0, LocalDate.now());

      assertEquals(100.0, result.percent());
      assertEquals(ActivityStatus.COMPLETED, result.status());
    }

    @Test
    @DisplayName("forces actualFinishDate when hitting 100% and not already set")
    void forcesActualFinish() {
      LocalDate now = LocalDate.of(2026, 4, 29);
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.UNITS);
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));
      activity.setActualFinishDate(null);

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, 100.0, 100.0, now);

      assertEquals(100.0, result.percent());
      assertEquals(now, result.forcedActualFinish());
    }

    @Test
    @DisplayName("does not overwrite existing actualFinishDate")
    void doesNotOverwriteActualFinish() {
      LocalDate existingFinish = LocalDate.of(2026, 4, 20);
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.UNITS);
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));
      activity.setActualFinishDate(existingFinish);

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, 100.0, 100.0, LocalDate.now());

      assertNull(result.forcedActualFinish());
    }

    @Test
    @DisplayName("zero planned units returns KEEP_PRIOR")
    void zeroPlannedReturnsKeepPrior() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.UNITS);

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, 0.0, 50.0, LocalDate.now());

      assertTrue(result.isKeepPrior());
    }

    @Test
    @DisplayName("null planned units returns KEEP_PRIOR")
    void nullPlannedReturnsKeepPrior() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.UNITS);

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, null, 50.0, LocalDate.now());

      assertTrue(result.isKeepPrior());
    }
  }

  @Nested
  @DisplayName("BOQ")
  class Boq {

    @Test
    @DisplayName("computes workdone / boqQty * 100")
    void computesBoqPercent() {
      Activity activity = new Activity();
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));

      PercentCompleteCalculator.Result result =
          calculator.calculateBoq(activity, 250.0, 1000.0, LocalDate.of(2026, 4, 20));

      assertEquals(25.0, result.percent());
      assertEquals(ActivityStatus.IN_PROGRESS, result.status());
      assertNull(result.forcedActualFinish());
    }

    @Test
    @DisplayName("caps at 100 and completes, forcing actualFinish when null")
    void capsAndCompletes() {
      LocalDate now = LocalDate.of(2026, 4, 29);
      Activity activity = new Activity();
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));
      activity.setActualFinishDate(null);

      PercentCompleteCalculator.Result result =
          calculator.calculateBoq(activity, 1200.0, 1000.0, now);

      assertEquals(100.0, result.percent());
      assertEquals(ActivityStatus.COMPLETED, result.status());
      assertEquals(now, result.forcedActualFinish());
    }

    @Test
    @DisplayName("does not overwrite an existing actualFinishDate")
    void keepsExistingFinish() {
      Activity activity = new Activity();
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));
      activity.setActualFinishDate(LocalDate.of(2026, 4, 20));

      PercentCompleteCalculator.Result result =
          calculator.calculateBoq(activity, 1000.0, 1000.0, LocalDate.of(2026, 4, 29));

      assertEquals(100.0, result.percent());
      assertNull(result.forcedActualFinish());
    }

    @Test
    @DisplayName("zero or null boqQty returns KEEP_PRIOR")
    void zeroBoqQtyKeepsPrior() {
      Activity activity = new Activity();
      assertTrue(calculator.calculateBoq(activity, 50.0, 0.0, LocalDate.now()).isKeepPrior());
      assertTrue(calculator.calculateBoq(activity, 50.0, null, LocalDate.now()).isKeepPrior());
    }
  }

  @Nested
  @DisplayName("DURATION")
  class Duration {

    @Test
    @DisplayName("actualFinishDate set → 100%")
    void actualFinishSetReturns100() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.DURATION);
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));
      activity.setActualFinishDate(LocalDate.of(2026, 4, 15));

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, null, null, LocalDate.now());

      assertEquals(100.0, result.percent());
      assertEquals(ActivityStatus.COMPLETED, result.status());
    }

    @Test
    @DisplayName("no actualStartDate → 0% NOT_STARTED")
    void noActualStartReturnsZero() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.DURATION);
      activity.setOriginalDuration(30.0);

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, null, null, LocalDate.now());

      assertEquals(0.0, result.percent());
      assertEquals(ActivityStatus.NOT_STARTED, result.status());
    }

    @Test
    @DisplayName("elapsed days / original duration * 100")
    void computesDurationPercent() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.DURATION);
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));
      activity.setOriginalDuration(30.0);

      // 18 days elapsed → 60%
      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, null, null, LocalDate.of(2026, 4, 19));

      assertEquals(60.0, result.percent());
      assertEquals(ActivityStatus.IN_PROGRESS, result.status());
    }

    @Test
    @DisplayName("caps at 100 for over-run durations and completes")
    void capsAt100() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.DURATION);
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));
      activity.setOriginalDuration(10.0);

      // 30 days elapsed on a 10-day activity → 100% (was 99.99)
      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, null, null, LocalDate.of(2026, 5, 1));

      assertEquals(100.0, result.percent());
      assertEquals(ActivityStatus.COMPLETED, result.status());
      assertEquals(LocalDate.of(2026, 5, 1), result.forcedActualFinish());
    }

    @Test
    @DisplayName("zero original duration → 0%")
    void zeroDurationReturnsZero() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.DURATION);
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));
      activity.setOriginalDuration(0.0);

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, null, null, LocalDate.of(2026, 4, 10));

      assertEquals(0.0, result.percent());
    }

    @Test
    @DisplayName("null original duration → 0%")
    void nullDurationReturnsZero() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.DURATION);
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));
      activity.setOriginalDuration(null);

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, null, null, LocalDate.of(2026, 4, 10));

      assertEquals(0.0, result.percent());
    }

    @Test
    @DisplayName("statusDate before actualStart → 0%")
    void statusDateBeforeStart() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.DURATION);
      activity.setActualStartDate(LocalDate.of(2026, 4, 10));
      activity.setOriginalDuration(30.0);

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, null, null, LocalDate.of(2026, 4, 1));

      assertEquals(0.0, result.percent());
    }
  }

  @Nested
  @DisplayName("Status derivation")
  class StatusDerivation {

    @Test
    @DisplayName("pct >= 100 → COMPLETED")
    void completedAt100() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.DURATION);
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));
      activity.setActualFinishDate(LocalDate.of(2026, 4, 15));

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, null, null, LocalDate.now());

      assertEquals(ActivityStatus.COMPLETED, result.status());
    }

    @Test
    @DisplayName("pct > 0 + actualStart → IN_PROGRESS")
    void inProgress() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.PHYSICAL);
      activity.setPhysicalPercentComplete(25.0);

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, null, null, LocalDate.now());

      assertEquals(ActivityStatus.IN_PROGRESS, result.status());
    }

    @Test
    @DisplayName("pct == 0 + no actualStart → NOT_STARTED")
    void notStarted() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(PercentCompleteType.PHYSICAL);
      activity.setPhysicalPercentComplete(0.0);

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, null, null, LocalDate.now());

      assertEquals(ActivityStatus.NOT_STARTED, result.status());
    }
  }

  @Nested
  @DisplayName("Default type (null)")
  class DefaultType {

    @Test
    @DisplayName("null percentCompleteType defaults to DURATION behavior")
    void defaultsToDuration() {
      Activity activity = new Activity();
      activity.setPercentCompleteType(null);
      activity.setActualStartDate(null);
      activity.setOriginalDuration(30.0);

      PercentCompleteCalculator.Result result = calculator.calculate(
          activity, null, null, LocalDate.now());

      assertEquals(0.0, result.percent());
      assertEquals(ActivityStatus.NOT_STARTED, result.status());
    }
  }
}
