package com.bipros.activity.domain.model;

/**
 * Lifecycle status for an Activity that is orthogonal to {@link ActivityStatus}.
 *
 * <p>{@link #DRAFT} — every Activity field is editable, but DPRs against the activity
 * are rejected (the activity is still being planned). New activities default to DRAFT.</p>
 *
 * <p>{@link #LOCKED} — manual edits via the Activity API and cross-module mutators
 * (scheduling, baseline, resource leveling, bulk ops) are rejected. DPR-driven
 * cascade writes (percentComplete / actualStart / actualFinish) still flow so
 * execution data continues to update.</p>
 */
public enum ActivityEditStatus {
  DRAFT,
  LOCKED
}
