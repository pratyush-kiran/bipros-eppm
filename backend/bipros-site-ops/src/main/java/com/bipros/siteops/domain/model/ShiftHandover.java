package com.bipros.siteops.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "shift_handovers", schema = "site_ops", indexes = {
        @Index(name = "ix_shift_handover_project_date_shift", columnList = "project_id, shift_date, shift"),
        @Index(name = "ix_shift_handover_to_user", columnList = "to_user_id, acknowledged_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ShiftHandover extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift", nullable = false, length = 10)
    private Shift shift;

    @Column(name = "from_user_id", nullable = false)
    private UUID fromUserId;

    @Column(name = "to_user_id", nullable = false)
    private UUID toUserId;

    @Column(name = "summary", nullable = false, length = 4000)
    private String summary;

    @Column(name = "pending_items", columnDefinition = "TEXT")
    private String pendingItems;

    @Column(name = "handed_over_at", nullable = false)
    private Instant handedOverAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;
}
