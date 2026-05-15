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
import java.util.UUID;

@Entity
@Table(name = "snags", schema = "site_ops", indexes = {
        @Index(name = "ix_snag_project_status", columnList = "project_id, status"),
        @Index(name = "ix_snag_project_severity", columnList = "project_id, severity"),
        @Index(name = "ix_snag_activity", columnList = "activity_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Snag extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "activity_id")
    private UUID activityId;

    @Column(name = "location_code", length = 200)
    private String locationCode;

    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private SnagSeverity severity = SnagSeverity.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SnagStatus status = SnagStatus.OPEN;

    @Column(name = "raised_by", nullable = false)
    private UUID raisedBy;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closure_note", length = 2000)
    private String closureNote;
}
