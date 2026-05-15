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
@Table(name = "safety_records", schema = "safety", indexes = {
        @Index(name = "ix_safety_record_project_kind", columnList = "project_id, kind"),
        @Index(name = "ix_safety_record_project_occurred", columnList = "project_id, occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SafetyRecord extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private SafetyKind kind;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "location_code", length = 200)
    private String locationCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private SafetySeverity severity = SafetySeverity.LOW;

    @Column(name = "description", nullable = false, length = 4000)
    private String description;

    @Column(name = "immediate_action", length = 4000)
    private String immediateAction;

    @Column(name = "reported_by")
    private UUID reportedBy;

    @Column(name = "people_involved", length = 2000)
    private String peopleInvolved;
}
