package com.bipros.activity.application.dto;

import java.util.List;

/**
 * Replaces the single-supervisor {@link SetSupervisorRequest}. Pass an empty
 * (or null) list to clear all supervisors; duplicate {@code userId} entries
 * are deduplicated server-side, the first occurrence wins for the name snapshot.
 */
public record SetSupervisorsRequest(List<SupervisorEntry> supervisors) {}
