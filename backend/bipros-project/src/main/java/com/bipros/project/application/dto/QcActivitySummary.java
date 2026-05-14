package com.bipros.project.application.dto;

import java.util.UUID;

public record QcActivitySummary(
    UUID activityId,
    String activityName,
    long pass,
    long fail,
    long repeat
) {}
