package com.bipros.reporting.application.dto;

import com.bipros.reporting.application.dto.CapacityUtilizationReport.Section;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Multi-period rollup of capacity utilization. Each bucket carries the same Manpower / Equipment
 * sections as the single-period report — re-using {@link Section} so frontends can render with
 * the existing role-row component. Buckets are non-overlapping; the {@code from}/{@code to}
 * pair is inclusive on both ends.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapacityUtilizationAggregateReport(
    UUID projectId,
    /** {@code WEEKLY} or {@code MONTHLY}. */
    String periodType,
    /** {@code ROLE} or {@code RESOURCE_TYPE}. */
    String groupBy,
    LocalDate fromDate,
    LocalDate toDate,
    List<Bucket> buckets
) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Bucket(
      LocalDate from,
      LocalDate to,
      /** Display label for the bucket (e.g. {@code 2026-W18} or {@code 2026-05}). */
      String label,
      Section manpower,
      Section equipment
  ) {}
}
