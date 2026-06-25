package com.bipros.api.dto;

import lombok.Data;
import java.util.List;

@Data
public class RepairRequest {
  /** Dry-run by default: compute + report, write nothing. */
  private boolean dryRun = true;
  /** Optional subset of phases; null/empty = all. Values: SUPERVISORS, RATE_LABELS, UNITS, RESCALE, REBUILD. */
  private List<String> phases;
}
