package com.bipros.project.application.dto;

import java.util.UUID;

/**
 * Lightweight option used by the Capacity-Utilization page's supervisor filter dropdown — only
 * supervisors who actually filed at least one DPR in the requested window are returned, so
 * users never see empty rows.
 *
 * <p>RBAC Phase 4.4 — the identity field carries a User UUID (FK to {@code public.users.id})
 * since the Phase 091 OLTP migration dropped {@code daily_progress_reports.supervisor_resource_id}.
 * The wire field is {@code supervisorUserId} to match the frontend contract.
 */
public record SupervisorOption(
    UUID supervisorUserId,
    String supervisorCode,
    String supervisorName,
    long dprCount) {}
