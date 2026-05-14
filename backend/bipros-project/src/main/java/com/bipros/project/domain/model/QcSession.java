package com.bipros.project.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * QC test session: one header per (project, activity, date) grouping.
 * Each session holds N test item rows — one row per test performed at the chainage range.
 */
@Entity
@Table(
    name = "qc_sessions",
    schema = "activity",
    indexes = {
        @Index(name = "idx_qc_sessions_project", columnList = "project_id"),
        @Index(name = "idx_qc_sessions_activity", columnList = "project_id, activity_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QcSession extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** Soft FK to activity.activities.id. */
    @Column(name = "activity_id", nullable = false)
    private UUID activityId;

    /** Snapshot of activity name at create time. */
    @Column(name = "activity_name", nullable = false, length = 150)
    private String activityName;

    @Column(name = "test_date", nullable = false)
    private LocalDate testDate;

    @Column(name = "chainage_from", length = 30)
    private String chainageFrom;

    @Column(name = "chainage_to", length = 30)
    private String chainageTo;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<QcTestItem> items = new ArrayList<>();
}
