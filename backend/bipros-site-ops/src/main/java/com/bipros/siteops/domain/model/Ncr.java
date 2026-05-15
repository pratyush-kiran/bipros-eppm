package com.bipros.siteops.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ncrs", schema = "ncr",
        indexes = {
                @Index(name = "ix_ncr_project_status", columnList = "project_id, status"),
                @Index(name = "ix_ncr_project_created", columnList = "project_id, created_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ncr_project_no", columnNames = {"project_id", "ncr_no"})
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Ncr extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "ncr_no", nullable = false, length = 40)
    private String ncrNo;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "description", length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private NcrCategory category = NcrCategory.QUALITY;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private NcrSeverity severity = NcrSeverity.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NcrStatus status = NcrStatus.OPEN;

    @Column(name = "raised_by")
    private UUID raisedBy;

    @Column(name = "raised_at")
    private Instant raisedAt;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "root_cause", length = 4000)
    private String rootCause;

    @Column(name = "corrective_action", length = 4000)
    private String correctiveAction;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;
}
