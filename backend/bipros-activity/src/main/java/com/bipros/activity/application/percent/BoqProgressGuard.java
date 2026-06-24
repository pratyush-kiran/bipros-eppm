package com.bipros.activity.application.percent;

import com.bipros.project.domain.repository.DailyProgressReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Single source of truth for "is this activity's progress driven by BOQ workdone?".
 * When true, BOQ workdone (precedence #1) owns {@code percentComplete} and the
 * type-based writers (DURATION / UNITS) must stand down so they don't clobber it.
 */
@Component
@RequiredArgsConstructor
public class BoqProgressGuard {

  private final DailyProgressReportRepository dprRepository;

  public boolean isBoqDriven(UUID activityId) {
    BigDecimal linkedBoqQty = dprRepository.sumLinkedBoqQty(activityId);
    return linkedBoqQty != null && linkedBoqQty.signum() > 0;
  }
}
