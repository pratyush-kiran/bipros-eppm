package com.bipros.project.application.dto;

import java.util.List;

/**
 * Lightweight DPR-preview payload — only the manpower + equipment rows are read; material is
 * intentionally excluded (it's consumed by the activity, not produced). Sent by the form on
 * each debounced change so the supervisor sees a live "expected output today" estimate.
 */
public record ProductivityPreviewRequest(
    List<DprManpowerRow> manpower,
    List<DprEquipmentRow> equipment) {}
