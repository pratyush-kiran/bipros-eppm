package com.bipros.reporting.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Wraps {@link ProjectStatusSnapshotDto} with period-over-period deltas for the executive
 * project dashboard. Every delta is nullable so the dashboard can render the snapshot
 * even before a snapshot-history feed exists. Once {@code project.daily_status_snapshots}
 * lands, the controller fills these in by diffing today's snapshot against the row from
 * seven days ago.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectStatusSnapshotWithTrendDto(
    ProjectStatusSnapshotDto current,
    SnapshotDeltas deltas) {

  public record SnapshotDeltas(
      Double physicalPctDelta,
      BigDecimal bacCroresDelta,
      Long activeRisksDelta,
      Long tasksCompletedDelta) {}
}
