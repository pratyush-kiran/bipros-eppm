package com.bipros.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Source-agnostic supervisor reference. After the role-only migration, a supervisor can be
 * either an application User (new path, {@code activity.supervisor_user_id}) or a legacy
 * manpower Resource (old DPRs, {@code dpr.supervisor_resource_id}). Reports must not care
 * which source the supervisor came from — they display the name and filter on whichever id
 * happens to be set.
 *
 * <p>Exactly one of {@code userId} / {@code resourceId} is expected to be set for a populated
 * ref; both can be null on the "all supervisors" sentinel.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorRef(String displayName, UUID userId, UUID resourceId) {

  public static SupervisorRef fromUser(UUID userId, String displayName) {
    return new SupervisorRef(displayName, userId, null);
  }

  public static SupervisorRef fromResource(UUID resourceId, String displayName) {
    return new SupervisorRef(displayName, null, resourceId);
  }

  public boolean isEmpty() {
    return userId == null && resourceId == null;
  }
}
