package com.bipros.siteops.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "checklist_instances", schema = "site_ops", indexes = {
        @Index(name = "ix_checklist_instance_project_status", columnList = "project_id, status"),
        @Index(name = "ix_checklist_instance_project_template", columnList = "project_id, template_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChecklistInstance extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "activity_id")
    private UUID activityId;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChecklistStatus status = ChecklistStatus.IN_PROGRESS;

    @Column(name = "started_by")
    private UUID startedBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "signed_by")
    private UUID signedBy;

    @Column(name = "signed_at")
    private Instant signedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "instance_id", nullable = false)
    private List<ChecklistAnswer> answers = new ArrayList<>();
}
