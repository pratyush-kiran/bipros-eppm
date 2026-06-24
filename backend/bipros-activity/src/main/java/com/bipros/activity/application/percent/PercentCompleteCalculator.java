package com.bipros.activity.application.percent;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.PercentCompleteType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * P6-aligned percent-complete calculator.
 * <ul>
 *   <li><b>PHYSICAL</b> — normalises {@link Activity#getPhysicalPercentComplete()} (what the
 *   step rollup writes), falling back to the current {@code percentComplete}, falling back to 0.</li>
 *   <li><b>UNITS</b> — {@code actualUnitsSum / plannedUnitsSum * 100}, capped at 100.
 *   Protects against division by zero (returns {@link Result#KEEP_PRIOR}).</li>
 *   <li><b>DURATION</b> — elapsed days since {@code actualStartDate} divided by
 *   {@code originalDuration}, capped at 100 (full elapsed time completes the activity).</li>
 * </ul>
 * Every branch derives {@link ActivityStatus} and optionally forces
 * {@code actualFinishDate = statusDate} (UNITS hitting 100%).
 */
@Component
public class PercentCompleteCalculator {

    /**
     * Compute percent complete.
     *
     * @param activity        the activity with current field values
     * @param plannedUnitsSum optional total planned units (UNITS branch only)
     * @param actualUnitsSum  optional total actual units (UNITS branch only)
     * @param statusDate      the data date / reference date for elapsed-day calculations
     * @return a {@link Result} — never null; check {@link Result#isKeepPrior()}
     */
    public Result calculate(Activity activity, Double plannedUnitsSum, Double actualUnitsSum, LocalDate statusDate) {
        PercentCompleteType type = activity.getPercentCompleteType();
        if (type == null) {
            type = PercentCompleteType.DURATION; // default per entity
        }
        return switch (type) {
            case PHYSICAL -> calculatePhysical(activity);
            case UNITS -> calculateUnits(activity, plannedUnitsSum, actualUnitsSum, statusDate);
            case DURATION -> calculateDuration(activity, statusDate);
        };
    }

    /**
     * BOQ workdone branch (precedence #1). Mirrors {@link #calculateUnits}: percent is
     * {@code workdone / boqQty * 100} capped at 100, with status derived and
     * {@code actualFinishDate} forced to {@code statusDate} on reaching 100.
     *
     * @param workdone this activity's own Σ qtyExecuted on its BOQ-linked DPRs
     * @param boqQty   Σ boqQty of the distinct BOQ items the activity references
     */
    public Result calculateBoq(Activity activity, Double workdone, Double boqQty, LocalDate statusDate) {
        if (boqQty == null || boqQty <= 0) {
            return Result.KEEP_PRIOR;
        }
        double raw = workdone != null ? (workdone / boqQty) * 100.0 : 0.0;
        double pct = Math.min(raw, 100.0);
        pct = round2(pct);
        ActivityStatus status = ActivityStatusDerivation.derive(pct, activity.getActualStartDate());
        LocalDate forcedActualFinish = (pct >= 100.0 && activity.getActualFinishDate() == null)
                ? statusDate : null;
        return new Result(pct, status, forcedActualFinish);
    }

    private Result calculatePhysical(Activity activity) {
        Double raw = activity.getPhysicalPercentComplete();
        if (raw == null) {
            raw = activity.getPercentComplete();
        }
        if (raw == null) {
            raw = 0.0;
        }
        double pct = clamp(raw, 0.0, 100.0);
        pct = round2(pct);
        return new Result(pct, ActivityStatusDerivation.derive(pct, activity.getActualStartDate()), null);
    }

    private Result calculateUnits(Activity activity, Double plannedSum, Double actualSum, LocalDate statusDate) {
        if (plannedSum == null || plannedSum <= 0) {
            return Result.KEEP_PRIOR;
        }
        double raw = actualSum != null ? (actualSum / plannedSum) * 100.0 : 0.0;
        double pct = Math.min(raw, 100.0);
        pct = round2(pct);
        ActivityStatus status = ActivityStatusDerivation.derive(pct, activity.getActualStartDate());
        LocalDate forcedActualFinish = (pct >= 100.0 && activity.getActualFinishDate() == null)
                ? statusDate : null;
        return new Result(pct, status, forcedActualFinish);
    }

    private Result calculateDuration(Activity activity, LocalDate statusDate) {
        // actualFinishDate set → 100%
        if (activity.getActualFinishDate() != null) {
            return new Result(100.0, ActivityStatus.COMPLETED, null);
        }
        // actualStartDate not set → 0%
        if (activity.getActualStartDate() == null) {
            return new Result(0.0, ActivityStatusDerivation.derive(0.0, null), null);
        }
        // In-progress: elapsed / originalDuration
        Double originalDuration = activity.getOriginalDuration();
        if (originalDuration == null || originalDuration <= 0) {
            return new Result(0.0, ActivityStatusDerivation.derive(0.0, activity.getActualStartDate()), null);
        }
        long elapsed = ChronoUnit.DAYS.between(activity.getActualStartDate(), statusDate);
        if (elapsed < 0) {
            elapsed = 0;
        }
        double raw = ((double) elapsed / originalDuration) * 100.0;
        double pct = Math.max(0.0, Math.min(raw, 100.0)); // full elapsed time → 100 (100 ⇔ Completed)
        pct = round2(pct);
        ActivityStatus status = ActivityStatusDerivation.derive(pct, activity.getActualStartDate());
        LocalDate forcedActualFinish = (pct >= 100.0 && activity.getActualFinishDate() == null)
                ? statusDate : null;
        return new Result(pct, status, forcedActualFinish);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * Computation result. {@link #isKeepPrior()} returns true when the calculator
     * cannot produce a meaningful value and callers should leave existing fields alone.
     */
    public record Result(Double percent, ActivityStatus status, LocalDate forcedActualFinish) {
        public boolean isKeepPrior() {
            return percent == null && status == null && forcedActualFinish == null;
        }

        public static final Result KEEP_PRIOR = new Result(null, null, null);
    }
}
