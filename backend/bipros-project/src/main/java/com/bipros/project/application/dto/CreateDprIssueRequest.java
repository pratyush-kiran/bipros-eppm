package com.bipros.project.application.dto;

import com.bipros.project.domain.model.IssueCategory;
import com.bipros.project.domain.model.IssueSeverity;
import com.bipros.project.domain.model.IssueStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body for creating a standalone DprIssue not tied to a parent DPR.
 * {@code reportDate} defaults to today server-side if null.
 * {@code status} defaults to OPEN if null.
 */
public record CreateDprIssueRequest(
    @NotBlank @Size(max = 150) String title,
    @Size(max = 2000) String description,
    @NotNull IssueCategory category,
    @NotNull IssueSeverity severity,
    IssueStatus status,
    UUID supervisorResourceId,
    String supervisorName,
    UUID assignedToResourceId,
    String assignedToName,
    UUID activityId,
    @Size(max = 150) String activityName,
    LocalDate reportDate
) {}
