package com.bipros.project.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Append-only audit log of each DPR approval status transition.
 * {@code createdAt} (from {@link BaseEntity}) records the transition time.
 */
@Entity
@Table(
    name = "dpr_approval_history",
    schema = "project",
    indexes = {
        @Index(name = "idx_dpr_approval_history_dpr", columnList = "dpr_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DprApprovalHistory extends BaseEntity {

  @Column(name = "dpr_id", nullable = false)
  private UUID dprId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 20)
  private DprApprovalStatus fromStatus;   // null for the initial transition

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 20)
  private DprApprovalStatus toStatus;

  @Column(name = "actor_user_id")
  private UUID actorUserId;                // null for system/seeder actions

  @Column(name = "reason", length = 1000)
  private String reason;
}
