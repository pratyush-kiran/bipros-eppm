package com.bipros.scheduling.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.scheduling.application.dto.FloatPathResponse;
import com.bipros.scheduling.application.dto.ScheduleActivityResultResponse;
import com.bipros.scheduling.application.dto.ScheduleRequest;
import com.bipros.scheduling.application.dto.ScheduleResultResponse;
import com.bipros.scheduling.application.service.SchedulingService;
import com.bipros.scheduling.domain.model.SchedulingOption;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/schedule")
@Slf4j
@RequiredArgsConstructor
public class ScheduleController {

  private final SchedulingService schedulingService;

  @PostMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SCHEDULE.UPDATE')")
  public ApiResponse<ScheduleResultResponse> scheduleProject(
      @PathVariable UUID projectId,
      @Valid @RequestBody(required = false) ScheduleRequest request) {
    log.info("Triggering schedule calculation for project: id={}", projectId);

    SchedulingOption option = request != null ? request.option() : SchedulingOption.RETAINED_LOGIC;
    ScheduleResultResponse result = schedulingService.scheduleProject(projectId, option);

    return ApiResponse.ok(result);
  }

  @GetMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SCHEDULE.READ')")
  public ApiResponse<ScheduleResultResponse> getLatestSchedule(@PathVariable UUID projectId) {
    log.debug("Fetching latest schedule for project: id={}", projectId);

    ScheduleResultResponse result = schedulingService.getLatestSchedule(projectId);
    return ApiResponse.ok(result);
  }

  @GetMapping("/critical-path")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SCHEDULE.READ')")
  public ApiResponse<List<ScheduleActivityResultResponse>> getCriticalPath(@PathVariable UUID projectId) {
    log.debug("Fetching critical path for project: id={}", projectId);

    List<ScheduleActivityResultResponse> activities = schedulingService.getCriticalPath(projectId);
    return ApiResponse.ok(activities);
  }

  @GetMapping("/float-paths")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SCHEDULE.READ')")
  public ApiResponse<List<FloatPathResponse>> getFloatPaths(@PathVariable UUID projectId) {
    log.debug("Fetching float paths for project: id={}", projectId);

    List<FloatPathResponse> paths = schedulingService.getFloatPaths(projectId);
    return ApiResponse.ok(paths);
  }

  @GetMapping("/activities")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SCHEDULE.READ')")
  public ApiResponse<List<ScheduleActivityResultResponse>> getAllScheduledActivities(
      @PathVariable UUID projectId) {
    log.debug("Fetching all scheduled activities for project: id={}", projectId);

    List<ScheduleActivityResultResponse> activities = schedulingService.getAllScheduledActivities(projectId);
    return ApiResponse.ok(activities);
  }
}
