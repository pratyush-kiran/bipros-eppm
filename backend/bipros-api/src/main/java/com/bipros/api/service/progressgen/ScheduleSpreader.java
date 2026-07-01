package com.bipros.api.service.progressgen;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Picks up to {@code count} distinct report dates in [start..today], all &lt;= today, ascending. */
@Component
public class ScheduleSpreader {

  public List<LocalDate> spread(LocalDate plannedStart, LocalDate today, int count) {
    int n = Math.max(count, 1);
    LocalDate start = (plannedStart == null || plannedStart.isAfter(today))
        ? today.minusDays(n - 1L) : plannedStart;
    long span = ChronoUnit.DAYS.between(start, today);     // >= 0
    List<LocalDate> out = new ArrayList<>();
    if (span <= 0) { out.add(today); return out; }
    for (int i = 0; i < n; i++) {
      long offset = Math.round((double) span * i / Math.max(n - 1, 1));
      LocalDate d = start.plusDays(offset);
      if (!out.contains(d)) out.add(d);
    }
    if (!out.contains(today)) out.set(out.size() - 1, today);
    return out;
  }
}
