package com.bipros.scheduling.infrastructure.adapter;

import com.bipros.calendar.application.service.CalendarService;
import com.bipros.calendar.application.service.CalendarSnapshot;
import com.bipros.scheduling.domain.algorithm.CalendarCalculator;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-run {@link CalendarCalculator} that batches calendar data into in-memory
 * {@link CalendarSnapshot}s — 2 SQL queries per unique calendar (work-weeks + exceptions),
 * then zero-DB working-day math for the rest of the CPM pass.
 *
 * <p>NOT a Spring singleton. Construct a fresh instance per schedule run in
 * {@code SchedulingService.scheduleProject} with a window that covers the full horizon.
 *
 * <p>The {@link #MAX_ITER} cap (default 100 000) guards against infinite loops when a calendar
 * has no working days at all. The field is package-private so unit tests can override it via
 * reflection without touching production code.
 */
public class SnapshotCalendarCalculator implements CalendarCalculator {

  /** Maximum day-step iterations before we give up and throw. Package-private for testability. */
  static int MAX_ITER = 100_000;

  private final CalendarService calendarService;
  private final LocalDate windowFrom;
  private final LocalDate windowTo;
  private final Map<UUID, CalendarSnapshot> cache = new HashMap<>();

  public SnapshotCalendarCalculator(
      CalendarService calendarService, LocalDate windowFrom, LocalDate windowTo) {
    this.calendarService = calendarService;
    this.windowFrom = windowFrom;
    this.windowTo = windowTo;
  }

  // ---- snapshot access (loads lazily, cached per calendarId) ----

  private CalendarSnapshot snapshot(UUID calendarId) {
    return cache.computeIfAbsent(
        calendarId, id -> calendarService.loadSnapshot(id, windowFrom, windowTo));
  }

  // ---- CalendarCalculator implementation ----

  @Override
  public boolean isWorkingDay(UUID calendarId, LocalDate date) {
    return snapshot(calendarId).isWorkingDay(date);
  }

  /**
   * Delegate to {@link CalendarService#getWorkingHours} — the CPM engine rarely/never calls this
   * in the critical hot-path, so a single DB call per invocation is acceptable.
   */
  @Override
  public double getWorkingHours(UUID calendarId, LocalDate date) {
    return calendarService.getWorkingHours(calendarId, date);
  }

  /**
   * Replicates {@link CalendarService#addWorkingDays} exactly (same loop semantics, same
   * negative-day delegation) but reads from the in-memory snapshot instead of the DB.
   *
   * <p>Convention: {@code addWorkingDays(start, 0) == start}, and the result is the date AFTER
   * the last consumed working day (earlyFinish = earlyStart + duration in CPM terms).
   */
  @Override
  public LocalDate addWorkingDays(UUID calendarId, LocalDate start, double days) {
    if (days < 0) {
      return subtractWorkingDays(calendarId, start, -days);
    }

    CalendarSnapshot snap = snapshot(calendarId);
    LocalDate current = start;
    double remaining = days;
    int iter = 0;
    while (remaining > 0) {
      if (iter++ >= MAX_ITER) {
        throw new IllegalStateException(
            "Calendar " + calendarId
                + " has no working days in the scheduling window (or exceeded "
                + MAX_ITER + " iterations)");
      }
      if (snap.isWorkingDay(current)) {
        remaining--;
      }
      current = current.plusDays(1);
    }
    return current;
  }

  /**
   * Replicates {@link CalendarService#subtractWorkingDays} exactly (same loop semantics, same
   * negative-day delegation) but reads from the in-memory snapshot instead of the DB.
   */
  @Override
  public LocalDate subtractWorkingDays(UUID calendarId, LocalDate from, double days) {
    if (days < 0) {
      return addWorkingDays(calendarId, from, -days);
    }

    CalendarSnapshot snap = snapshot(calendarId);
    LocalDate current = from;
    double remaining = days;
    int iter = 0;
    while (remaining > 0) {
      if (iter++ >= MAX_ITER) {
        throw new IllegalStateException(
            "Calendar " + calendarId
                + " has no working days in the scheduling window (or exceeded "
                + MAX_ITER + " iterations)");
      }
      current = current.minusDays(1);
      if (snap.isWorkingDay(current)) {
        remaining--;
      }
    }
    return current;
  }

  /** Delegates to the snapshot's {@code countWorkingDays} — already in-memory. */
  @Override
  public double countWorkingDays(UUID calendarId, LocalDate start, LocalDate end) {
    return snapshot(calendarId).countWorkingDays(start, end);
  }
}
