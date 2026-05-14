package com.bipros.project.application.dto;

import java.util.List;

public record QcDashboardResponse(
    long totalTests,
    long passCount,
    long failCount,
    long repeatCount,
    Double passRate,
    long todayTests,
    List<QcActivitySummary> byActivity,
    List<QcTestItemResponse> recentFails
) {}
