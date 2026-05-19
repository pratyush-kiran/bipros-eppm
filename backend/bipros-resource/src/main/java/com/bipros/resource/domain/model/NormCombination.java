package com.bipros.resource.domain.model;

/**
 * How a Work Activity combines its Manpower and Equipment productivity norms into the single
 * "expected output / day" figure shown on the DPR preview. Has no effect when only one side has
 * a norm — the single side drives the expected output regardless.
 *
 * <p>Default is {@link #SERIES} so existing activities preserve the historical {@code min()}
 * bottleneck behaviour after migration.
 */
public enum NormCombination {
  /** Manpower and equipment work on the same unit of output, in sequence. Expected = min(MP, EQ). */
  SERIES,
  /** Manpower and equipment work independently on different stretches. Expected = MP + EQ. */
  PARALLEL,
  /** Either side alone completes the unit; the slower one is redundant. Expected = max(MP, EQ). */
  SUBSTITUTE
}
