package com.bipros.project.application.dto;

/**
 * Request body for DPR approval transitions (approve / reject / revoke).
 * {@code reason} is optional for approve/revoke, required for reject.
 */
public record DprApprovalActionRequest(String reason) {}
