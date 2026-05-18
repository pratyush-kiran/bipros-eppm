package com.bipros.project.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One row per (project, user, role) on the project-scoped reporting line. Backs the Daily
 * Balance Sheet rollup: a Supervisor's row points {@link #reportsToUserId} at the Engineer,
 * the Engineer's row points at the Site Manager / PM, and so on. Project-scoped because the
 * same user may be a supervisor here and an engineer there.
 */
@Entity
@Table(
    name = "project_team",
    schema = "project",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_project_team_user_role",
            columnNames = {"project_id", "user_id", "role"})
    },
    indexes = {
        @Index(name = "idx_project_team_project", columnList = "project_id"),
        @Index(name = "idx_project_team_user", columnList = "user_id"),
        @Index(name = "idx_project_team_role", columnList = "role")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectTeamMember extends BaseEntity {

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 32)
  private ProjectRole role;

  /** The user this member reports to on this project (null for the PM at the top). */
  @Column(name = "reports_to_user_id")
  private UUID reportsToUserId;

  @Column(name = "active_from")
  private LocalDate activeFrom;

  @Column(name = "active_to")
  private LocalDate activeTo;
}
