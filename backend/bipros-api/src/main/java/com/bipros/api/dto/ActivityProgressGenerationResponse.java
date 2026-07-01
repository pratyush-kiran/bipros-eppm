package com.bipros.api.dto;

import java.util.List;

public record ActivityProgressGenerationResponse(
    boolean dryRun, int activitiesTargeted, int activitiesGenerated,
    int activitiesSkipped, int dprsCreated, List<ActivityProgressResult> results) {}
