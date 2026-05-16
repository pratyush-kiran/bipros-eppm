package com.bipros.activity.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class GlobalChangeService {

  private final ActivityRepository activityRepository;

  /**
   * Apply a bulk change to every activity that matches the filter, skipping any
   * activity that is {@code LOCKED} — locked activities are silently excluded so
   * a typo-fix on 1000 activities doesn't fail because three are locked. Callers
   * surface the {@code skippedLocked} count in the UI.
   */
  public GlobalChangeResult applyGlobalChange(UUID projectId, GlobalChangeRequest request) {
    log.info("Applying global change for project: projectId={}, filterField={}, filterValue={}, " +
            "updateField={}, updateValue={}, operation={}",
        projectId, request.filterField(), request.filterValue(),
        request.updateField(), request.updateValue(), request.operation());

    List<Activity> activities = activityRepository.findByProjectId(projectId);
    int updatedCount = 0;
    List<String> skippedLockedCodes = new ArrayList<>();

    for (Activity activity : activities) {
      if (matchesFilter(activity, request.filterField(), request.filterValue())) {
        if (activity.getEditStatus() == ActivityEditStatus.LOCKED) {
          skippedLockedCodes.add(activity.getCode());
          continue;
        }
        applyUpdate(activity, request.updateField(), request.updateValue(), request.operation());
        activityRepository.save(activity);
        updatedCount++;
      }
    }

    log.info("Global change applied: projectId={}, updatedCount={}, skippedLocked={}",
        projectId, updatedCount, skippedLockedCodes.size());
    return new GlobalChangeResult(updatedCount, skippedLockedCodes.size(), skippedLockedCodes);
  }

  private boolean matchesFilter(Activity activity, String filterField, String filterValue) {
    return switch (filterField.toLowerCase()) {
      case "status" -> activity.getStatus() != null &&
          activity.getStatus().name().equals(filterValue.toUpperCase());
      case "isCritical" -> activity.getIsCritical() != null &&
          activity.getIsCritical().toString().equals(filterValue);
      case "code" -> activity.getCode() != null && activity.getCode().contains(filterValue);
      case "name" -> activity.getName() != null && activity.getName().contains(filterValue);
      default -> false;
    };
  }

  private void applyUpdate(Activity activity, String updateField, String updateValue,
      GlobalChangeOperation operation) {
    try {
      switch (updateField.toLowerCase()) {
        case "status" -> {
          try {
            ActivityStatus status = ActivityStatus.valueOf(updateValue.toUpperCase());
            activity.setStatus(status);
          } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("INVALID_STATUS",
                "Invalid status value: " + updateValue);
          }
        }
        case "originalDuration" -> {
          double currentValue = activity.getOriginalDuration() != null ?
              activity.getOriginalDuration() : 0.0;
          double updateVal = Double.parseDouble(updateValue);
          activity.setOriginalDuration(applyNumericOperation(currentValue, updateVal, operation));
        }
        case "remainingDuration" -> {
          double currentValue = activity.getRemainingDuration() != null ?
              activity.getRemainingDuration() : 0.0;
          double updateVal = Double.parseDouble(updateValue);
          activity.setRemainingDuration(applyNumericOperation(currentValue, updateVal, operation));
        }
        case "percentComplete" -> {
          double currentValue = activity.getPercentComplete() != null ?
              activity.getPercentComplete() : 0.0;
          double updateVal = Double.parseDouble(updateValue);
          double result = applyNumericOperation(currentValue, updateVal, operation);
          if (result < 0 || result > 100) {
            throw new BusinessRuleException("INVALID_PERCENT_COMPLETE",
                "Percent complete must be between 0 and 100");
          }
          activity.setPercentComplete(result);
        }
        default -> throw new BusinessRuleException("UNSUPPORTED_FIELD",
            "Unsupported update field: " + updateField);
      }
    } catch (NumberFormatException e) {
      throw new BusinessRuleException("INVALID_VALUE",
          "Invalid value for numeric field: " + updateValue);
    }
  }

  private double applyNumericOperation(double currentValue, double updateValue,
      GlobalChangeOperation operation) {
    return switch (operation) {
      case SET -> updateValue;
      case ADD -> currentValue + updateValue;
      case SUBTRACT -> currentValue - updateValue;
    };
  }
}
