package com.bipros.ai.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ActivityAiGenerationService#compressToWindow} — the from-scratch
 * "fit the generated plan inside the project window" behavior.
 */
@DisplayName("compressToWindow — fit from-scratch schedule into the project window")
class ActivityAiScheduleCompressionTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    @Test
    @DisplayName("compresses an over-long plan so every activity falls inside the window")
    void compressesOverLongPlan() {
        LocalDate deadline = START.plusDays(2); // 2-day project window
        Map<String, LocalDate> starts = new LinkedHashMap<>();
        Map<String, LocalDate> finishes = new LinkedHashMap<>();
        // Natural plan spans 30 days — far beyond the 2-day window.
        starts.put("A", START);              finishes.put("A", START.plusDays(10));
        starts.put("B", START.plusDays(10)); finishes.put("B", START.plusDays(30));

        ActivityAiGenerationService.compressToWindow(starts, finishes, START, deadline);

        for (String code : starts.keySet()) {
            assertThat(starts.get(code)).as("%s start within window", code).isAfterOrEqualTo(START);
            assertThat(finishes.get(code)).as("%s finish within window", code).isBeforeOrEqualTo(deadline);
            assertThat(starts.get(code)).as("%s start <= finish", code).isBeforeOrEqualTo(finishes.get(code));
        }
        // Full window is used and ordering is preserved (A finishes no later than B starts).
        assertThat(finishes.get("B")).isEqualTo(deadline);
        assertThat(finishes.get("A")).isBeforeOrEqualTo(starts.get("B"));
    }

    @Test
    @DisplayName("leaves a plan that already fits unchanged")
    void noOpWhenPlanFits() {
        LocalDate deadline = START.plusDays(60); // generous window
        Map<String, LocalDate> starts = new LinkedHashMap<>();
        Map<String, LocalDate> finishes = new LinkedHashMap<>();
        starts.put("A", START);              finishes.put("A", START.plusDays(10));
        starts.put("B", START.plusDays(10)); finishes.put("B", START.plusDays(30));

        ActivityAiGenerationService.compressToWindow(starts, finishes, START, deadline);

        assertThat(starts.get("A")).isEqualTo(START);
        assertThat(finishes.get("A")).isEqualTo(START.plusDays(10));
        assertThat(starts.get("B")).isEqualTo(START.plusDays(10));
        assertThat(finishes.get("B")).isEqualTo(START.plusDays(30));
    }

    @Test
    @DisplayName("guarantees nothing exceeds the window even for many sequential activities")
    void allWithinWindowForManyActivities() {
        LocalDate deadline = START.plusDays(5);
        Map<String, LocalDate> starts = new LinkedHashMap<>();
        Map<String, LocalDate> finishes = new LinkedHashMap<>();
        // 15 sequential activities, 5 days each → 75-day natural span.
        LocalDate cursor = START;
        for (int i = 1; i <= 15; i++) {
            starts.put("ACT-" + i, cursor);
            cursor = cursor.plusDays(5);
            finishes.put("ACT-" + i, cursor);
        }

        ActivityAiGenerationService.compressToWindow(starts, finishes, START, deadline);

        for (String code : starts.keySet()) {
            assertThat(starts.get(code)).isAfterOrEqualTo(START);
            assertThat(finishes.get(code)).isBeforeOrEqualTo(deadline);
            assertThat(starts.get(code)).isBeforeOrEqualTo(finishes.get(code));
        }
    }
}
