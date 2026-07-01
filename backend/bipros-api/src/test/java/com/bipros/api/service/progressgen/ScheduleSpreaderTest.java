package com.bipros.api.service.progressgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleSpreaderTest {

  private final ScheduleSpreader s = new ScheduleSpreader();

  @Test
  void spreadsAcrossPastWindow() {
    List<LocalDate> d = s.spread(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), 3);
    assertThat(d).hasSize(3).isSorted();
    assertThat(d).allSatisfy(x -> assertThat(x).isBeforeOrEqualTo(LocalDate.of(2026, 6, 30)));
  }

  @Test
  void futurePlanStartClampsToRecentPastWindow() {
    List<LocalDate> d = s.spread(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 6, 30), 3);
    assertThat(d).containsExactly(
        LocalDate.of(2026, 6, 28),
        LocalDate.of(2026, 6, 29),
        LocalDate.of(2026, 6, 30));
  }

  @Test
  void neverExceedsToday() {
    List<LocalDate> d = s.spread(null, LocalDate.of(2026, 6, 30), 1);
    assertThat(d).containsExactly(LocalDate.of(2026, 6, 30));
  }
}
