package com.bipros.ai.activity.dto;

import java.util.List;

public record ActivityAiNode(
        String code,
        String name,
        String description,
        String wbsNodeCode,
        Double originalDurationDays,
        List<AiPredecessor> predecessors
) {
}
