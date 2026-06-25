package com.bipros.api.dto;

import java.util.List;
import java.util.Map;

public record RepairReport(
    boolean dryRun,
    List<String> phases,
    Map<String, Integer> changedByPhase,
    DataHealthResponse before,
    DataHealthResponse after) {}
