package com.bipros.scheduling.infrastructure.adapter;

import com.bipros.calendar.application.service.CalendarService;
import com.bipros.calendar.application.service.CalendarSnapshot;
import com.bipros.calendar.domain.model.CalendarWorkWeek;
import com.bipros.calendar.domain.model.DayType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SnapshotCalendarCalculator")
class SnapshotCalendarCalculatorTest {

  @Mock
  private CalendarService calendarService;

  private UUID calId;
  private SnapshotCalendarCalculator calc;

  /** Mon–Fri working, Sat–Sun non-working snapshot (no exceptions). */
  private CalendarSnapshot monFriSnapshot;

  /** All-non-working snapshot — used to test the iteration cap. */
  private CalendarSnapshot noWorkingDaysSnapshot;

  @BeforeEach
  void setUp() {
    calId = UUID.randomUUID();

    // Build Mon–Fri work-week map
    Map<DayOfWeek, CalendarWorkWeek> workWeek = new EnumMap<>(DayOfWeek.class);
    for (DayOfWeek d : DayOfWeek.values()) {
      DayType type = (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY)
          ? DayType.NON_WORKING : DayType.WORKING;
      workWeek.put(d, CalendarWorkWeek.builder()
          .calendarId(calId)
          .dayOfWeek(d)
          .dayType(type)
          .totalWorkHours(type == DayType.WORKING ? 8.0 : 0.0)
          .build());
    }
    monFriSnapshot = new CalendarSnapshot(calId, workWeek, Collections.emptyMap());

    // All-non-working (empty work-week map → every day non-working)
    noWorkingDaysSnapshot = new CalendarSnapshot(calId, Collections.emptyMap(), Collections.emptyMap());

    // Default window — wide enough for all tests
    LocalDate windowFrom = LocalDate.of(2020, 1, 1);
    LocalDate windowTo   = LocalDate.of(2035, 1, 1);
    calc = new SnapshotCalendarCalculator(calendarService, windowFrom, windowTo);
  }

  // -------------------------------------------------------------------------
  // addWorkingDays — date-equivalence
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("addWorkingDays: 5 working days from Monday (Jan 6) returns Saturday Jan 11 (matches CalendarService loop semantics)")
  void addWorkingDays_fiveFromMonday_landsSaturday() {
    when(calendarService.loadSnapshot(eq(calId), any(), any())).thenReturn(monFriSnapshot);

    // CalendarService.addWorkingDays loop:
    //   counts Mon,Tue,Wed,Thu,Fri → remaining reaches 0, then increments current once more.
    //   So Mon Jan 6 + 5 = Sat Jan 11 (the day AFTER the 5th working day).
    // This is the "earlyFinish = earlyStart + duration" CPM convention.
    LocalDate start    = LocalDate.of(2025, 1, 6);   // Monday
    LocalDate expected = LocalDate.of(2025, 1, 11);  // Saturday (the day after Fri Jan 10)

    assertEquals(expected, calc.addWorkingDays(calId, start, 5.0));
  }

  @Test
  @DisplayName("addWorkingDays: 0 days returns start unchanged")
  void addWorkingDays_zeroDays_returnsStart() {
    when(calendarService.loadSnapshot(eq(calId), any(), any())).thenReturn(monFriSnapshot);

    LocalDate start = LocalDate.of(2025, 1, 6); // Monday
    assertEquals(start, calc.addWorkingDays(calId, start, 0.0));
  }

  @Test
  @DisplayName("addWorkingDays: 1 working day from Friday returns Saturday (day after Friday)")
  void addWorkingDays_oneDayFromFriday_returnsSaturday() {
    when(calendarService.loadSnapshot(eq(calId), any(), any())).thenReturn(monFriSnapshot);

    // CalendarService loop: Fri Jan 10 is working → remaining 0, current advances to Sat Jan 11.
    // Loop exits. Returns Sat Jan 11 — consistent with CPM earlyFinish semantics.
    LocalDate friday = LocalDate.of(2025, 1, 10);
    LocalDate expected = LocalDate.of(2025, 1, 11); // Saturday

    assertEquals(expected, calc.addWorkingDays(calId, friday, 1.0));
  }

  // -------------------------------------------------------------------------
  // subtractWorkingDays / negative delegation
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("addWorkingDays with negative days delegates to subtractWorkingDays (same result)")
  void addWorkingDays_negativeDelegation() {
    when(calendarService.loadSnapshot(eq(calId), any(), any())).thenReturn(monFriSnapshot);

    LocalDate start = LocalDate.of(2025, 1, 13); // Monday
    LocalDate viaAdd      = calc.addWorkingDays(calId, start, -2.0);
    LocalDate viaSubtract = calc.subtractWorkingDays(calId, start, 2.0);

    assertEquals(viaSubtract, viaAdd,
        "addWorkingDays(d, -n) must equal subtractWorkingDays(d, n)");
  }

  @Test
  @DisplayName("subtractWorkingDays with negative days delegates to addWorkingDays")
  void subtractWorkingDays_negativeDelegation() {
    when(calendarService.loadSnapshot(eq(calId), any(), any())).thenReturn(monFriSnapshot);

    LocalDate start = LocalDate.of(2025, 1, 6); // Monday
    LocalDate viaSubtract = calc.subtractWorkingDays(calId, start, -5.0);
    LocalDate viaAdd      = calc.addWorkingDays(calId, start, 5.0);

    assertEquals(viaAdd, viaSubtract,
        "subtractWorkingDays(d, -n) must equal addWorkingDays(d, n)");
  }

  @Test
  @DisplayName("subtractWorkingDays: 5 days back from Monday lands on previous Monday")
  void subtractWorkingDays_fiveFromMonday() {
    when(calendarService.loadSnapshot(eq(calId), any(), any())).thenReturn(monFriSnapshot);

    // Monday 2025-01-13 − 5 working days → Monday 2025-01-06
    LocalDate start    = LocalDate.of(2025, 1, 13);
    LocalDate expected = LocalDate.of(2025, 1, 6);

    assertEquals(expected, calc.subtractWorkingDays(calId, start, 5.0));
  }

  // -------------------------------------------------------------------------
  // countWorkingDays
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("countWorkingDays: same start and end returns 0 (half-open interval)")
  void countWorkingDays_sameDay_zero() {
    when(calendarService.loadSnapshot(eq(calId), any(), any())).thenReturn(monFriSnapshot);

    LocalDate d = LocalDate.of(2025, 1, 6);
    assertEquals(0.0, calc.countWorkingDays(calId, d, d));
  }

  @Test
  @DisplayName("countWorkingDays: Mon–Fri week [Mon, nextMon) = 5 working days")
  void countWorkingDays_fullWeek_five() {
    when(calendarService.loadSnapshot(eq(calId), any(), any())).thenReturn(monFriSnapshot);

    LocalDate mon     = LocalDate.of(2025, 1, 6);
    LocalDate nextMon = LocalDate.of(2025, 1, 13);

    assertEquals(5.0, calc.countWorkingDays(calId, mon, nextMon));
  }

  // -------------------------------------------------------------------------
  // Caching — loadSnapshot called only once per calendarId
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("loadSnapshot is called exactly once per calendarId even across many method calls")
  void cachingLoadSnapshotOnce() {
    when(calendarService.loadSnapshot(eq(calId), any(), any())).thenReturn(monFriSnapshot);

    LocalDate monday = LocalDate.of(2025, 1, 6);
    calc.isWorkingDay(calId, monday);
    calc.addWorkingDays(calId, monday, 3.0);
    calc.subtractWorkingDays(calId, monday.plusDays(3), 3.0);
    calc.countWorkingDays(calId, monday, monday.plusDays(7));

    verify(calendarService, times(1)).loadSnapshot(eq(calId), any(), any());
  }

  @Test
  @DisplayName("loadSnapshot is called once per UNIQUE calendarId (separate calendars each load once)")
  void cachingTwoCalendars_eachLoadOnce() {
    UUID calId2 = UUID.randomUUID();
    CalendarSnapshot snap2 = new CalendarSnapshot(calId2, buildMonFriWorkWeek(calId2), Collections.emptyMap());

    when(calendarService.loadSnapshot(eq(calId), any(), any())).thenReturn(monFriSnapshot);
    when(calendarService.loadSnapshot(eq(calId2), any(), any())).thenReturn(snap2);

    LocalDate d = LocalDate.of(2025, 1, 6);
    calc.isWorkingDay(calId, d);
    calc.isWorkingDay(calId, d.plusDays(1));
    calc.isWorkingDay(calId2, d);
    calc.isWorkingDay(calId2, d.plusDays(1));

    verify(calendarService, times(1)).loadSnapshot(eq(calId), any(), any());
    verify(calendarService, times(1)).loadSnapshot(eq(calId2), any(), any());
  }

  // -------------------------------------------------------------------------
  // Iteration cap — throws on all-non-working calendar
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("addWorkingDays throws IllegalStateException when calendar has no working days (cap hit)")
  void addWorkingDays_noWorkingDays_throwsAfterCap() {
    when(calendarService.loadSnapshot(eq(calId), any(), any())).thenReturn(noWorkingDaysSnapshot);

    // Lower the cap so the test completes quickly
    int originalCap = SnapshotCalendarCalculator.MAX_ITER;
    SnapshotCalendarCalculator.MAX_ITER = 10;
    try {
      assertThrows(IllegalStateException.class,
          () -> calc.addWorkingDays(calId, LocalDate.of(2025, 1, 6), 1.0),
          "Should throw when no working days and cap is exceeded");
    } finally {
      SnapshotCalendarCalculator.MAX_ITER = originalCap;
    }
  }

  @Test
  @DisplayName("subtractWorkingDays throws IllegalStateException when calendar has no working days (cap hit)")
  void subtractWorkingDays_noWorkingDays_throwsAfterCap() {
    when(calendarService.loadSnapshot(eq(calId), any(), any())).thenReturn(noWorkingDaysSnapshot);

    int originalCap = SnapshotCalendarCalculator.MAX_ITER;
    SnapshotCalendarCalculator.MAX_ITER = 10;
    try {
      assertThrows(IllegalStateException.class,
          () -> calc.subtractWorkingDays(calId, LocalDate.of(2025, 1, 6), 1.0),
          "Should throw when no working days and cap is exceeded");
    } finally {
      SnapshotCalendarCalculator.MAX_ITER = originalCap;
    }
  }

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private Map<DayOfWeek, CalendarWorkWeek> buildMonFriWorkWeek(UUID calendarId) {
    Map<DayOfWeek, CalendarWorkWeek> map = new EnumMap<>(DayOfWeek.class);
    for (DayOfWeek d : DayOfWeek.values()) {
      DayType type = (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY)
          ? DayType.NON_WORKING : DayType.WORKING;
      map.put(d, CalendarWorkWeek.builder()
          .calendarId(calendarId)
          .dayOfWeek(d)
          .dayType(type)
          .totalWorkHours(type == DayType.WORKING ? 8.0 : 0.0)
          .build());
    }
    return map;
  }
}
