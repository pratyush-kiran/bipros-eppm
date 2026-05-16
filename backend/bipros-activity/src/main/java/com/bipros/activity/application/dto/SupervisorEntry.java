package com.bipros.activity.application.dto;

import java.util.UUID;

/**
 * One supervisor on an activity. Activity carries a list of these — all equal,
 * no "primary" semantics. Used both in {@link SetSupervisorsRequest} (write)
 * and inside {@link ActivityResponse#supervisors()} (read).
 */
public record SupervisorEntry(UUID userId, String userName) {}
