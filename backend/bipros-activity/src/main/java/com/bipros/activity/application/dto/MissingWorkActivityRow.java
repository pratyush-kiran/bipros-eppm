package com.bipros.activity.application.dto;

import java.util.UUID;

/**
 * Slim activity reference used by the "activities with no Work Activity linked" listing on the
 * Capacity Utilization page. Only the fields the UI needs to render the [Review] CTA and route
 * the user to the activity drawer.
 */
public record MissingWorkActivityRow(
    UUID activityId,
    String code,
    String name,
    Integer dprCount) {}
