package com.bipros.calendar.application.service;

import com.bipros.calendar.application.dto.CalendarDetailResponse;
import com.bipros.calendar.application.dto.CalendarExceptionRequest;
import com.bipros.calendar.application.dto.CalendarExceptionResponse;
import com.bipros.calendar.application.dto.CalendarResponse;
import com.bipros.calendar.application.dto.CalendarWorkWeekRequest;
import com.bipros.calendar.application.dto.CalendarWorkWeekResponse;
import com.bipros.calendar.application.dto.CreateCalendarRequest;
import com.bipros.calendar.domain.model.Calendar;
import com.bipros.calendar.domain.model.CalendarException;
import com.bipros.calendar.domain.model.CalendarType;
import com.bipros.calendar.domain.model.CalendarWorkWeek;
import com.bipros.calendar.domain.model.DayType;
import com.bipros.calendar.domain.repository.CalendarActivityCounter;
import com.bipros.calendar.domain.repository.CalendarExceptionRepository;
import com.bipros.calendar.domain.repository.CalendarRepository;
import com.bipros.calendar.domain.repository.CalendarWorkWeekRepository;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class CalendarService {

  private final CalendarRepository calendarRepository;
  private final CalendarWorkWeekRepository workWeekRepository;
  private final CalendarExceptionRepository exceptionRepository;
  private final CalendarActivityCounter calendarActivityCounter;
  private final AuditService auditService;

  /**
   * Create a new calendar with default work week pattern (Mon-Fri working 08:00-12:00 +
   * 13:00-17:00, Sat-Sun non-working).
   */
  public CalendarResponse createCalendar(CreateCalendarRequest request) {
    log.info(
        "Creating calendar: name={}, type={}, projectId={}, resourceId={}",
        request.name(),
        request.calendarType(),
        request.projectId(),
        request.resourceId());

    Calendar calendar = Calendar.builder()
        .name(request.name())
        .description(request.description())
        .calendarType(request.calendarType())
        .projectId(request.projectId())
        .resourceId(request.resourceId())
        .parentCalendarId(request.parentCalendarId())
        .isDefault(false)
        .standardWorkHoursPerDay(
            request.standardWorkHoursPerDay() != null ? request.standardWorkHoursPerDay() : 8.0)
        .standardWorkDaysPerWeek(
            request.standardWorkDaysPerWeek() != null ? request.standardWorkDaysPerWeek() : 5)
        .build();

    Calendar saved = calendarRepository.save(calendar);
    log.debug("Calendar created with id={}", saved.getId());
    auditService.logCreate("Calendar", saved.getId(), CalendarResponse.from(saved));

    // Create default work week pattern
    createDefaultWorkWeek(saved.getId());

    return CalendarResponse.from(saved);
  }

  /** Update calendar properties. */
  public CalendarResponse updateCalendar(UUID id, CreateCalendarRequest request) {
    log.info("Updating calendar: id={}", id);

    Calendar calendar = calendarRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Calendar", id));

    calendar.setName(request.name());
    calendar.setDescription(request.description());
    if (request.standardWorkHoursPerDay() != null) {
      calendar.setStandardWorkHoursPerDay(request.standardWorkHoursPerDay());
    }
    if (request.standardWorkDaysPerWeek() != null) {
      calendar.setStandardWorkDaysPerWeek(request.standardWorkDaysPerWeek());
    }

    Calendar updated = calendarRepository.save(calendar);
    log.debug("Calendar updated with id={}", id);
    auditService.logUpdate("Calendar", id, "calendar", calendar, updated);
    return CalendarResponse.from(updated);
  }

  /** Delete calendar (checks no activities reference it). */
  public void deleteCalendar(UUID id) {
    log.info("Deleting calendar: id={}", id);

    Calendar calendar = calendarRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Calendar", id));

    // Check if activities or projects use this calendar
    long activityCount = calendarActivityCounter.countActivitiesByCalendarId(id);
    if (activityCount > 0) {
      throw new BusinessRuleException("CALENDAR_IN_USE", "Cannot delete calendar that is in use");
    }

    workWeekRepository.deleteByCalendarId(id);
    exceptionRepository.deleteByCalendarId(id);
    calendarRepository.deleteById(id);
    auditService.logDelete("Calendar", id);

    log.debug("Calendar deleted with id={}", id);
  }

  /** Get calendar with all work weeks and exceptions. */
  @Transactional(readOnly = true)
  public CalendarDetailResponse getCalendar(UUID id) {
    log.debug("Fetching calendar detail: id={}", id);

    Calendar calendar = calendarRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Calendar", id));

    List<CalendarWorkWeekResponse> workWeeks = workWeekRepository.findByCalendarId(id)
        .stream()
        .map(CalendarWorkWeekResponse::from)
        .toList();

    List<CalendarExceptionResponse> exceptions = exceptionRepository.findByCalendarId(id)
        .stream()
        .map(CalendarExceptionResponse::from)
        .toList();

    return CalendarDetailResponse.from(calendar, workWeeks, exceptions);
  }

  /** List all calendars of a specific type. */
  @Transactional(readOnly = true)
  public List<CalendarResponse> listCalendars(CalendarType type) {
    log.debug("Listing calendars of type={}", type);
    return calendarRepository.findByCalendarType(type).stream()
        .map(CalendarResponse::from)
        .toList();
  }

  /** List all calendars regardless of type. */
  @Transactional(readOnly = true)
  public List<CalendarResponse> listAllCalendars() {
    log.debug("Listing all calendars");
    return calendarRepository.findAll().stream()
        .map(CalendarResponse::from)
        .toList();
  }

  /** Get the default calendar of a specific type. */
  @Transactional(readOnly = true)
  public CalendarResponse getDefaultCalendar(CalendarType type) {
    log.debug("Fetching default calendar of type={}", type);

    return calendarRepository.findByCalendarTypeAndIsDefaultTrue(type)
        .map(CalendarResponse::from)
        .orElseThrow(
            () -> new BusinessRuleException(
                "NO_DEFAULT_CALENDAR",
                "No default calendar found for type: " + type));
  }

  /** Replace all 7 days of the work week for a calendar. */
  public List<CalendarWorkWeekResponse> setWorkWeek(
      UUID calendarId, List<CalendarWorkWeekRequest> requests) {
    log.info("Setting work week for calendar: id={}, days={}", calendarId, requests.size());

    Calendar calendar = calendarRepository.findById(calendarId)
        .orElseThrow(() -> new ResourceNotFoundException("Calendar", calendarId));

    // Delete existing work weeks. Must flush before the subsequent saveAll so the DELETE
    // hits the DB before the INSERT — otherwise the (calendar_id, day_of_week) unique
    // constraint fires because Hibernate holds both rows in the persistence context.
    workWeekRepository.deleteByCalendarId(calendarId);
    workWeekRepository.flush();

    // Create new ones
    List<CalendarWorkWeek> workWeeks = requests.stream()
        .map(req -> {
          DayOfWeek dayOfWeek = DayOfWeek.valueOf(req.dayOfWeek().toUpperCase());
          Double totalHours = calculateTotalWorkHours(
              req.startTime1(), req.endTime1(), req.startTime2(), req.endTime2());

          return CalendarWorkWeek.builder()
              .calendarId(calendarId)
              .dayOfWeek(dayOfWeek)
              .dayType(req.dayType())
              .startTime1(req.startTime1())
              .endTime1(req.endTime1())
              .startTime2(req.startTime2())
              .endTime2(req.endTime2())
              .totalWorkHours(totalHours)
              .build();
        })
        .toList();

    List<CalendarWorkWeek> saved = workWeekRepository.saveAll(workWeeks);
    log.debug("Work week set for calendar: id={}, days={}", calendarId, saved.size());
    auditService.logUpdate("CalendarWorkWeek", calendarId, "workWeek", workWeeks, saved);

    return saved.stream().map(CalendarWorkWeekResponse::from).toList();
  }

  /** Add an exception day. */
  public CalendarExceptionResponse addException(
      UUID calendarId, CalendarExceptionRequest request) {
    log.info("Adding exception to calendar: id={}, date={}", calendarId, request.exceptionDate());

    Calendar calendar = calendarRepository.findById(calendarId)
        .orElseThrow(() -> new ResourceNotFoundException("Calendar", calendarId));

    // Check if exception already exists
    if (exceptionRepository
        .findByCalendarIdAndExceptionDate(calendarId, request.exceptionDate())
        .isPresent()) {
      throw new BusinessRuleException(
          "EXCEPTION_EXISTS",
          "Exception already exists for date: " + request.exceptionDate());
    }

    Double totalHours = calculateTotalWorkHours(
        request.startTime1(), request.endTime1(), request.startTime2(), request.endTime2());

    CalendarException exception = CalendarException.builder()
        .calendarId(calendarId)
        .exceptionDate(request.exceptionDate())
        .dayType(request.dayType())
        .name(request.name())
        .startTime1(request.startTime1())
        .endTime1(request.endTime1())
        .startTime2(request.startTime2())
        .endTime2(request.endTime2())
        .totalWorkHours(totalHours)
        .build();

    CalendarException saved = exceptionRepository.save(exception);
    log.debug("Exception added to calendar: id={}, exceptionId={}", calendarId, saved.getId());
    auditService.logCreate("CalendarException", saved.getId(), CalendarExceptionResponse.from(saved));

    return CalendarExceptionResponse.from(saved);
  }

  /** Remove an exception day. */
  public void removeException(UUID exceptionId) {
    log.info("Removing exception: id={}", exceptionId);

    CalendarException exception = exceptionRepository.findById(exceptionId)
        .orElseThrow(() -> new ResourceNotFoundException("CalendarException", exceptionId));

    exceptionRepository.deleteById(exceptionId);
    auditService.logDelete("CalendarException", exceptionId);
    log.debug("Exception removed: id={}", exceptionId);
  }

  /** Get exceptions for a calendar within a date range. */
  @Transactional(readOnly = true)
  public List<CalendarExceptionResponse> getExceptions(
      UUID calendarId, LocalDate start, LocalDate end) {
    log.debug("Fetching exceptions for calendar: id={}, range={}..{}", calendarId, start, end);

    return exceptionRepository.findByCalendarIdAndExceptionDateBetween(calendarId, start, end)
        .stream()
        .map(CalendarExceptionResponse::from)
        .toList();
  }

  // ========== Calendar Calculation Helpers ==========

  /**
   * Load a {@link CalendarSnapshot} for the given calendar and date range — 2 SQL queries
   * (work-week rows + exceptions in range), then unlimited in-memory {@code isWorkingDay} /
   * {@code countWorkingDays} calls. Use this whenever a single request needs many working-day
   * lookups against the same calendar (e.g. the resource-usage time-phased report). The
   * per-call {@link #isWorkingDay(UUID, LocalDate)} and {@link #countWorkingDays(UUID, LocalDate, LocalDate)}
   * are convenient for one-off scheduler ops but become an N+1 trap when looped.
   */
  @Transactional(readOnly = true)
  public CalendarSnapshot loadSnapshot(UUID calendarId, LocalDate from, LocalDate to) {
    log.debug("Loading calendar snapshot: id={}, range={}..{}", calendarId, from, to);
    Map<DayOfWeek, CalendarWorkWeek> workWeekByDay = new HashMap<>();
    for (CalendarWorkWeek w : workWeekRepository.findByCalendarId(calendarId)) {
      workWeekByDay.put(w.getDayOfWeek(), w);
    }
    Map<LocalDate, CalendarException> exceptionByDate = new HashMap<>();
    for (CalendarException ex : exceptionRepository.findByCalendarIdAndExceptionDateBetween(calendarId, from, to)) {
      exceptionByDate.put(ex.getExceptionDate(), ex);
    }
    return new CalendarSnapshot(calendarId, workWeekByDay, exceptionByDate);
  }

  /** Check if a date is a working day (considering exceptions and work week pattern). */
  @Transactional(readOnly = true)
  public boolean isWorkingDay(UUID calendarId, LocalDate date) {
    // Check exceptions first
    Optional<CalendarException> exception =
        exceptionRepository.findByCalendarIdAndExceptionDate(calendarId, date);
    if (exception.isPresent()) {
      DayType dayType = exception.get().getDayType();
      return dayType == DayType.EXCEPTION_WORKING;
    }

    // Check work week pattern
    DayOfWeek dayOfWeek = date.getDayOfWeek();
    Optional<CalendarWorkWeek> workWeek =
        workWeekRepository.findByCalendarIdAndDayOfWeek(calendarId, dayOfWeek);

    if (workWeek.isPresent()) {
      DayType dayType = workWeek.get().getDayType();
      return dayType == DayType.WORKING;
    }

    return false;
  }

  /** Get working hours for a specific date. */
  @Transactional(readOnly = true)
  public double getWorkingHours(UUID calendarId, LocalDate date) {
    // Check exceptions first
    Optional<CalendarException> exception =
        exceptionRepository.findByCalendarIdAndExceptionDate(calendarId, date);
    if (exception.isPresent()) {
      CalendarException ex = exception.get();
      if (ex.getDayType() == DayType.EXCEPTION_NON_WORKING) {
        return 0.0;
      }
      return ex.getTotalWorkHours() != null ? ex.getTotalWorkHours() : 0.0;
    }

    // Check work week pattern
    DayOfWeek dayOfWeek = date.getDayOfWeek();
    Optional<CalendarWorkWeek> workWeek =
        workWeekRepository.findByCalendarIdAndDayOfWeek(calendarId, dayOfWeek);

    if (workWeek.isPresent()) {
      CalendarWorkWeek ww = workWeek.get();
      if (ww.getDayType() == DayType.NON_WORKING) {
        return 0.0;
      }
      return ww.getTotalWorkHours() != null ? ww.getTotalWorkHours() : 0.0;
    }

    return 0.0;
  }

  /**
   * Add working days to a start date using "start-of-day" convention: the returned date is the
   * date AFTER the last day of work. So {@code addWorkingDays(start, 0) == start}, and
   * {@code addWorkingDays(monday, 5)} returns the following Monday (after five working days of
   * Mon-Fri). Matches the CPM scheduler's convention where earlyFinish = earlyStart + duration.
   */
  @Transactional(readOnly = true)
  public LocalDate addWorkingDays(UUID calendarId, LocalDate start, double days) {
    log.debug("Adding {} working days from {} for calendar: id={}", days, start, calendarId);

    if (days < 0) {
      return subtractWorkingDays(calendarId, start, -days);
    }

    LocalDate current = start;
    double remaining = days;
    while (remaining > 0) {
      if (isWorkingDay(calendarId, current)) {
        remaining--;
      }
      current = current.plusDays(1);
    }
    return current;
  }

  /**
   * Subtract working days from a date. Inverse of {@link #addWorkingDays}:
   * {@code subtractWorkingDays(addWorkingDays(d, n), n) == d} when bounds land on working days.
   */
  @Transactional(readOnly = true)
  public LocalDate subtractWorkingDays(UUID calendarId, LocalDate from, double days) {
    log.debug("Subtracting {} working days from {} for calendar: id={}", days, from, calendarId);

    if (days < 0) {
      return addWorkingDays(calendarId, from, -days);
    }

    LocalDate current = from;
    double remaining = days;
    while (remaining > 0) {
      current = current.minusDays(1);
      if (isWorkingDay(calendarId, current)) {
        remaining--;
      }
    }
    return current;
  }

  /**
   * Count working days in the half-open interval [start, end). Same-day returns 0 so this is
   * consistent with {@link #addWorkingDays}: the count of working days from {@code d} to
   * {@code addWorkingDays(d, n)} is {@code n}.
   */
  @Transactional(readOnly = true)
  public double countWorkingDays(UUID calendarId, LocalDate start, LocalDate end) {
    log.debug("Counting working days from {} to {} for calendar: id={}", start, end, calendarId);

    if (!start.isBefore(end)) {
      return 0.0;
    }
    double count = 0;
    LocalDate current = start;
    while (current.isBefore(end)) {
      if (isWorkingDay(calendarId, current)) {
        count++;
      }
      current = current.plusDays(1);
    }
    return count;
  }

  // ========== Private Helper Methods ==========

  /** Create default work week pattern (Mon-Fri 08:00-12:00 + 13:00-17:00, Sat-Sun off). */
  private void createDefaultWorkWeek(UUID calendarId) {
    LocalTime morningStart = LocalTime.of(8, 0);
    LocalTime morningEnd = LocalTime.of(12, 0);
    LocalTime afternoonStart = LocalTime.of(13, 0);
    LocalTime afternoonEnd = LocalTime.of(17, 0);
    double workingDayHours = 8.0;

    // Mon-Fri: working
    for (int i = 1; i <= 5; i++) {
      DayOfWeek day = DayOfWeek.of(i);
      workWeekRepository.save(
          CalendarWorkWeek.builder()
              .calendarId(calendarId)
              .dayOfWeek(day)
              .dayType(DayType.WORKING)
              .startTime1(morningStart)
              .endTime1(morningEnd)
              .startTime2(afternoonStart)
              .endTime2(afternoonEnd)
              .totalWorkHours(workingDayHours)
              .build());
    }

    // Sat-Sun: non-working
    for (int i = 6; i <= 7; i++) {
      DayOfWeek day = DayOfWeek.of(i);
      workWeekRepository.save(
          CalendarWorkWeek.builder()
              .calendarId(calendarId)
              .dayOfWeek(day)
              .dayType(DayType.NON_WORKING)
              .totalWorkHours(0.0)
              .build());
    }

    log.debug("Default work week created for calendar: id={}", calendarId);
  }

  /** Calculate total work hours from time ranges. */
  private Double calculateTotalWorkHours(
      LocalTime startTime1,
      LocalTime endTime1,
      LocalTime startTime2,
      LocalTime endTime2) {
    double totalHours = 0.0;

    if (startTime1 != null && endTime1 != null) {
      long minutes = java.time.temporal.ChronoUnit.MINUTES.between(startTime1, endTime1);
      totalHours += minutes / 60.0;
    }

    if (startTime2 != null && endTime2 != null) {
      long minutes = java.time.temporal.ChronoUnit.MINUTES.between(startTime2, endTime2);
      totalHours += minutes / 60.0;
    }

    return totalHours > 0 ? totalHours : null;
  }
}
