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
@Table(name = "workfronts", schema = "site_ops", indexes = {
        @Index(name = "ix_workfront_project_status", columnList = "project_id, status"),
        @Index(name = "ix_workfront_project_wbs", columnList = "project_id, wbs_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Workfront extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "wbs_code", length = 64)
    private String wbsCode;

    @Column(name = "location_code", length = 200)
    private String locationCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private WorkfrontStatus status = WorkfrontStatus.PLANNED;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "released_by")
    private UUID releasedBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "blockers", length = 2000)
    private String blockers;

    @Column(name = "notes", length = 2000)
    private String notes;
}
