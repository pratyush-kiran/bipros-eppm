package com.bipros.calendar.presentation.controller;

import com.bipros.calendar.application.dto.CalendarDetailResponse;
import com.bipros.calendar.application.dto.CalendarExceptionRequest;
import com.bipros.calendar.application.dto.CalendarExceptionResponse;
import com.bipros.calendar.application.dto.CalendarResponse;
import com.bipros.calendar.application.dto.CalendarWorkWeekRequest;
import com.bipros.calendar.application.dto.CalendarWorkWeekResponse;
import com.bipros.calendar.application.dto.CreateCalendarRequest;
import com.bipros.calendar.application.service.CalendarService;
import com.bipros.calendar.domain.model.CalendarType;
import com.bipros.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/calendars")
@RequiredArgsConstructor
@Slf4j
public class CalendarController {

  private final CalendarService calendarService;

  /** POST / - Create a new calendar. */
  @PostMapping
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<CalendarResponse>> createCalendar(
      @Valid @RequestBody CreateCalendarRequest request) {
    log.info("POST /v1/calendars - Creating calendar: {}", request.name());
    CalendarResponse response = calendarService.createCalendar(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  /** GET / - List calendars (filter by type). */
  @GetMapping
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
  public ResponseEntity<ApiResponse<List<CalendarResponse>>> listCalendars(
      @RequestParam(required = false) CalendarType type) {
    log.info("GET /v1/calendars - Listing calendars, type={}", type);
    List<CalendarResponse> calendars = type != null
        ? calendarService.listCalendars(type)
        : calendarService.listAllCalendars();
    return ResponseEntity.ok(ApiResponse.ok(calendars));
  }

  /** GET /{id} - Get calendar detail (with work weeks + exceptions). */
  @GetMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
  public ResponseEntity<ApiResponse<CalendarDetailResponse>> getCalendar(
      @PathVariable UUID id) {
    log.info("GET /v1/calendars/{} - Fetching calendar detail", id);
    CalendarDetailResponse response = calendarService.getCalendar(id);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  /** PUT /{id} - Update calendar. */
  @PutMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<CalendarResponse>> updateCalendar(
      @PathVariable UUID id, @Valid @RequestBody CreateCalendarRequest request) {
    log.info("PUT /v1/calendars/{} - Updating calendar", id);
    CalendarResponse response = calendarService.updateCalendar(id, request);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  /** DELETE /{id} - Delete calendar. */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> deleteCalendar(@PathVariable UUID id) {
    log.info("DELETE /v1/calendars/{} - Deleting calendar", id);
    calendarService.deleteCalendar(id);
    return ResponseEntity.noContent().build();
  }

  /** PUT /{id}/work-week - Set work week. */
  @PutMapping("/{id}/work-week")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<List<CalendarWorkWeekResponse>>> setWorkWeek(
      @PathVariable UUID id, @Valid @RequestBody List<CalendarWorkWeekRequest> requests) {
    log.info("PUT /v1/calendars/{}/work-week - Setting work week, days={}", id, requests.size());
    List<CalendarWorkWeekResponse> response = calendarService.setWorkWeek(id, requests);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  /** POST /{id}/exceptions - Add exception. */
  @PostMapping("/{id}/exceptions")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<CalendarExceptionResponse>> addException(
      @PathVariable UUID id, @Valid @RequestBody CalendarExceptionRequest request) {
    log.info(
        "POST /v1/calendars/{}/exceptions - Adding exception: date={}",
        id,
        request.exceptionDate());
    CalendarExceptionResponse response = calendarService.addException(id, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  /** DELETE /{id}/exceptions/{exceptionId} - Remove exception. */
  @DeleteMapping("/{id}/exceptions/{exceptionId}")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
  public ResponseEntity<ApiResponse<Void>> removeException(
      @PathVariable UUID id, @PathVariable UUID exceptionId) {
    log.info("DELETE /v1/calendars/{}/exceptions/{} - Removing exception", id, exceptionId);
    calendarService.removeException(exceptionId);
    return ResponseEntity.noContent().build();
  }

  /** GET /{id}/exceptions - Get exceptions in range. */
  @GetMapping("/{id}/exceptions")
  @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
  public ResponseEntity<ApiResponse<List<CalendarExceptionResponse>>> getExceptions(
      @PathVariable UUID id,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
    log.info("GET /v1/calendars/{}/exceptions?start={}&end={} - Fetching exceptions", id, start, end);
    List<CalendarExceptionResponse> exceptions = calendarService.getExceptions(id, start, end);
    return ResponseEntity.ok(ApiResponse.ok(exceptions));
  }
}
