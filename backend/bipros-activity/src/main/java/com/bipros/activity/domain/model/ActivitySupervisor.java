package com.bipros.activity.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Join row linking an Activity to one of its supervisors. Replaces the single
 * {@code Activity.supervisor_user_id} column to support the real-world case
 * where one activity (e.g. a single BOQ line on a road project) is co-supervised
 * by several site engineers. The legacy column on {@code activities} is kept in
 * sync with the first entry for backward compatibility but new readers should
 * join through this table.
 */
@Entity
@Table(
    name = "activity_supervisors",
    schema = "activity",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_activity_supervisor", columnNames = {"activity_id", "user_id"})
    },
    indexes = {
        @Index(name = "idx_activity_supervisors_activity", columnList = "activity_id"),
        @Index(name = "idx_activity_supervisors_user", columnList = "user_id")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ActivitySupervisor extends BaseEntity {

  @Column(name = "activity_id", nullable = false)
  private UUID activityId;

  /** Soft FK to {@code public.users.id}. */
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  /** Display-snapshot of the user's name at assignment time — avoids a join to public.users on read. */
  @Column(name = "user_name_snapshot", length = 255)
  private String userNameSnapshot;
}
