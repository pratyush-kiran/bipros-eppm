package com.bipros.calendar.application.service;

import com.bipros.calendar.application.dto.CalendarWorkWeekRequest;
import com.bipros.calendar.domain.model.Calendar;
import com.bipros.calendar.domain.model.CalendarType;
import com.bipros.calendar.domain.model.CalendarWorkWeek;
import com.bipros.calendar.domain.model.DayType;
import com.bipros.calendar.domain.repository.CalendarActivityCounter;
import com.bipros.calendar.domain.repository.CalendarExceptionRepository;
import com.bipros.calendar.domain.repository.CalendarRepository;
import com.bipros.calendar.domain.repository.CalendarWorkWeekRepository;
import com.bipros.common.util.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CalendarService Tests")
class CalendarServiceTest {

  @Mock
  private CalendarRepository calendarRepository;

  @Mock
  private CalendarWorkWeekRepository workWeekRepository;

  @Mock
  private CalendarExceptionRepository exceptionRepository;

  @Mock
  private CalendarActivityCounter calendarActivityCounter;

  @Mock
  private AuditService auditService;

  private CalendarService calendarService;

  private UUID calendarId;

  @BeforeEach
  void setUp() {
    calendarService = new CalendarService(calendarRepository, workWeekRepository, exceptionRepository, calendarActivityCounter, auditService);
    calendarId = UUID.randomUUID();
  }

  @Nested
  @DisplayName("Working day detection")
  class WorkingDayTests {

    @Test
    @DisplayName("Monday is a working day")
    void mondayIsWorkingDay() {
      LocalDate monday = LocalDate.of(2025, 1, 6);
      assertEquals(DayOfWeek.MONDAY, monday.getDayOfWeek());

      // Mock exception repository to return no exception
      lenient()
          .when(exceptionRepository.findByCalendarIdAndExceptionDate(eq(calendarId), eq(monday)))
          .thenReturn(Optional.empty());

      // Mock work week for Monday
      CalendarWorkWeek mondayWorkWeek = createWorkWeek(DayOfWeek.MONDAY, DayType.WORKING);
      lenient()
          .when(workWeekRepository.findByCalendarIdAndDayOfWeek(eq(calendarId), eq(DayOfWeek.MONDAY)))
          .thenReturn(Optional.of(mondayWorkWeek));

      boolean result = calendarService.isWorkingDay(calendarId, monday);

      assertTrue(result);
    }

    @Test
    @DisplayName("Friday is a working day")
    void fridayIsWorkingDay() {
      LocalDate friday = LocalDate.of(2025, 1, 10);
      assertEquals(DayOfWeek.FRIDAY, friday.getDayOfWeek());

      // Mock exception repository to return no exception
      lenient()
          .when(exceptionRepository.findByCalendarIdAndExceptionDate(eq(calendarId), eq(friday)))
          .thenReturn(Optional.empty());

      // Mock work week for Friday
      CalendarWorkWeek fridayWorkWeek = createWorkWeek(DayOfWeek.FRIDAY, DayType.WORKING);
      lenient()
          .when(workWeekRepository.findByCalendarIdAndDayOfWeek(eq(calendarId), eq(DayOfWeek.FRIDAY)))
          .thenReturn(Optional.of(fridayWorkWeek));

      boolean result = calendarService.isWorkingDay(calendarId, friday);

      assertTrue(result);
    }

    @Test
    @DisplayName("Saturday is not a working day")
    void saturdayIsNotWorkingDay() {
      LocalDate saturday = LocalDate.of(2025, 1, 11);
      assertEquals(DayOfWeek.SATURDAY, saturday.getDayOfWeek());

      // Mock exception repository to return no exception
      lenient()
          .when(exceptionRepository.findByCalendarIdAndExceptionDate(eq(calendarId), eq(saturday)))
          .thenReturn(Optional.empty());

      // Mock work week for Saturday (non-working)
      CalendarWorkWeek saturdayWorkWeek = createWorkWeek(DayOfWeek.SATURDAY, DayType.NON_WORKING);
      lenient()
          .when(workWeekRepository.findByCalendarIdAndDayOfWeek(eq(calendarId), eq(DayOfWeek.SATURDAY)))
          .thenReturn(Optional.of(saturdayWorkWeek));

      boolean result = calendarService.isWorkingDay(calendarId, saturday);

      assertFalse(result);
    }

    @Test
    @DisplayName("Sunday is not a working day")
    void sundayIsNotWorkingDay() {
      LocalDate sunday = LocalDate.of(2025, 1, 12);
      assertEquals(DayOfWeek.SUNDAY, sunday.getDayOfWeek());

      // Mock exception repository to return no exception
      lenient()
          .when(exceptionRepository.findByCalendarIdAndExceptionDate(eq(calendarId), eq(sunday)))
          .thenReturn(Optional.empty());

      // Mock work week for Sunday (non-working)
      CalendarWorkWeek sundayWorkWeek = createWorkWeek(DayOfWeek.SUNDAY, DayType.NON_WORKING);
      lenient()
          .when(workWeekRepository.findByCalendarIdAndDayOfWeek(eq(calendarId), eq(DayOfWeek.SUNDAY)))
          .thenReturn(Optional.of(sundayWorkWeek));

      boolean result = calendarService.isWorkingDay(calendarId, sunday);

      assertFalse(result);
    }
  }

  @Nested
  @DisplayName("Add working days")
  class AddWorkingDaysTests {

    @Test
    @DisplayName("adding 5 working days skips weekends")
    void addFiveWorkingDaysSkipsWeekends() {
      LocalDate startDate = LocalDate.of(2025, 1, 6); // Monday

      // Mock work week pattern for all 7 days
      for (int i = 1; i <= 5; i++) {
        DayOfWeek day = DayOfWeek.of(i);
        CalendarWorkWeek workWeek = createWorkWeek(day, DayType.WORKING);
        lenient()
            .when(workWeekRepository.findByCalendarIdAndDayOfWeek(eq(calendarId), eq(day)))
            .thenReturn(Optional.of(workWeek));
      }

      for (int i = 6; i <= 7; i++) {
        DayOfWeek day = DayOfWeek.of(i);
        CalendarWorkWeek workWeek = createWorkWeek(day, DayType.NON_WORKING);
        lenient()
            .when(workWeekRepository.findByCalendarIdAndDayOfWeek(eq(calendarId), eq(day)))
            .thenReturn(Optional.of(workWeek));
      }

      // Mock no exceptions for all dates
      for (int offset = 0; offset < 10; offset++) {
        lenient()
            .when(exceptionRepository.findByCalendarIdAndExceptionDate(eq(calendarId), eq(startDate.plusDays(offset))))
            .thenReturn(Optional.empty());
      }

      // Convention: addWorkingDays returns the day AFTER the last worked day (matches CPM
      // "earlyFinish = earlyStart + duration" semantics). Mon 2025-01-06 + 5 working days
      // consumes Mon..Fri and returns Sat 2025-01-11 (the day after Fri). Follow-up calls
      // starting from Sat skip the weekend before counting the next working day.
      LocalDate result = calendarService.addWorkingDays(calendarId, startDate, 5);

      assertEquals(LocalDate.of(2025, 1, 11), result);
      assertEquals(DayOfWeek.SATURDAY, result.getDayOfWeek());
    }

    @Test
    @DisplayName("adding 0 working days returns same date")
    void addZeroWorkingDaysReturnsSameDate() {
      LocalDate startDate = LocalDate.of(2025, 1, 6);

      LocalDate result = calendarService.addWorkingDays(calendarId, startDate, 0);

      assertEquals(startDate, result);
    }

    @Test
    @DisplayName("adding negative days delegates to subtractWorkingDays (lead)")
    void addNegativeWorkingDaysDelegatesToSubtract() {
      // Wednesday 2025-01-08; subtract 2 working days should land on Monday 2025-01-06
      LocalDate startDate = LocalDate.of(2025, 1, 8); // Wednesday

      // Mock Mon-Fri working, Sat-Sun non-working
      for (int i = 1; i <= 5; i++) {
        DayOfWeek day = DayOfWeek.of(i);
        lenient()
            .when(workWeekRepository.findByCalendarIdAndDayOfWeek(eq(calendarId), eq(day)))
            .thenReturn(Optional.of(createWorkWeek(day, DayType.WORKING)));
      }
      for (int i = 6; i <= 7; i++) {
        DayOfWeek day = DayOfWeek.of(i);
        lenient()
            .when(workWeekRepository.findByCalendarIdAndDayOfWeek(eq(calendarId), eq(day)))
            .thenReturn(Optional.of(createWorkWeek(day, DayType.NON_WORKING)));
      }
      for (int offset = -5; offset <= 0; offset++) {
        lenient()
            .when(exceptionRepository.findByCalendarIdAndExceptionDate(eq(calendarId), eq(startDate.plusDays(offset))))
            .thenReturn(Optional.empty());
      }

      // addWorkingDays(..., -2) must equal subtractWorkingDays(..., 2) and move backward
      LocalDate viaAdd = calendarService.addWorkingDays(calendarId, startDate, -2);
      LocalDate viaSubtract = calendarService.subtractWorkingDays(calendarId, startDate, 2);

      assertEquals(viaSubtract, viaAdd, "addWorkingDays with negative days must equal subtractWorkingDays");
      assertEquals(LocalDate.of(2025, 1, 6), viaAdd, "lead of 2 from Wednesday should land on Monday");
    }
  }

  @Nested
  @DisplayName("Subtract working days")
  class SubtractWorkingDaysTests {

    @Test
    @DisplayName("subtracting 5 working days skips weekends")
    void subtractFiveWorkingDaysSkipsWeekends() {
      LocalDate endDate = LocalDate.of(2025, 1, 10); // Friday

      // Mock work week pattern for all 7 days
      for (int i = 1; i <= 5; i++) {
        DayOfWeek day = DayOfWeek.of(i);
        CalendarWorkWeek workWeek = createWorkWeek(day, DayType.WORKING);
        lenient()
            .when(workWeekRepository.findByCalendarIdAndDayOfWeek(eq(calendarId), eq(day)))
            .thenReturn(Optional.of(workWeek));
      }

      for (int i = 6; i <= 7; i++) {
        DayOfWeek day = DayOfWeek.of(i);
        CalendarWorkWeek workWeek = createWorkWeek(day, DayType.NON_WORKING);
        lenient()
            .when(workWeekRepository.findByCalendarIdAndDayOfWeek(eq(calendarId), eq(day)))
            .thenReturn(Optional.of(workWeek));
      }

      // Mock no exceptions for all dates
      for (int offset = -7; offset <= 0; offset++) {
        lenient()
            .when(exceptionRepository.findByCalendarIdAndExceptionDate(eq(calendarId), eq(endDate.plusDays(offset))))
            .thenReturn(Optional.empty());
      }

      // subtractWorkingDays is the inverse of addWorkingDays under the new convention:
      // starting from Fri 2025-01-10 and stepping back past 5 working days lands on the Monday
      // one week earlier (2025-01-06 is still within Mon-Fri of the target week after skipping
      // the preceding weekend).
      LocalDate result = calendarService.subtractWorkingDays(calendarId, endDate, 5);

      assertEquals(LocalDate.of(2025, 1, 3), result);
      assertEquals(DayOfWeek.FRIDAY, result.getDayOfWeek());
    }
  }

  @Nested
  @DisplayName("setWorkWeek (replace all 7 days)")
  class SetWorkWeek {

    @Test
    @DisplayName("flushes repository between delete and insert to avoid unique-constraint hit")
    void flushesBeforeInsert() {
      Calendar calendar = Calendar.builder().calendarType(CalendarType.PROJECT).build();
      calendar.setId(calendarId);
      when(calendarRepository.findById(calendarId)).thenReturn(Optional.of(calendar));
      when(workWeekRepository.saveAll(any(List.class))).thenAnswer(inv -> inv.getArgument(0));

      List<CalendarWorkWeekRequest> requests = List.of(
          new CalendarWorkWeekRequest("MONDAY", DayType.WORKING,
              LocalTime.of(8, 0), LocalTime.of(12, 0),
              LocalTime.of(13, 0), LocalTime.of(17, 0)),
          new CalendarWorkWeekRequest("SUNDAY", DayType.NON_WORKING, null, null, null, null)
      );

      calendarService.setWorkWeek(calendarId, requests);

      // The fix: delete -> flush -> saveAll, in that order, so the DELETE hits the DB
      // before the INSERT and the (calendar_id, day_of_week) unique constraint doesn't fire.
      var order = inOrder(workWeekRepository);
      order.verify(workWeekRepository).deleteByCalendarId(calendarId);
      order.verify(workWeekRepository).flush();
      order.verify(workWeekRepository).saveAll(any(List.class));
    }
  }

  // Helper methods

  private CalendarWorkWeek createWorkWeek(DayOfWeek dayOfWeek, DayType dayType) {
    CalendarWorkWeek workWeek = new CalendarWorkWeek();
    workWeek.setCalendarId(calendarId);
    workWeek.setDayOfWeek(dayOfWeek);
    workWeek.setDayType(dayType);
    if (dayType == DayType.WORKING) {
      workWeek.setStartTime1(LocalTime.of(8, 0));
      workWeek.setEndTime1(LocalTime.of(12, 0));
      workWeek.setStartTime2(LocalTime.of(13, 0));
      workWeek.setEndTime2(LocalTime.of(17, 0));
      workWeek.setTotalWorkHours(8.0);
    }
    return workWeek;
  }
}
