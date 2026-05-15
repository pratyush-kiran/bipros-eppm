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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "attendance_records",
        schema = "site_ops",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_attendance_project_date_contractor_skill",
                        columnNames = {"project_id", "attendance_date", "contractor_name", "skill_category"}
                )
        },
        indexes = {
                @Index(name = "ix_attendance_project_date", columnList = "project_id, attendance_date"),
                @Index(name = "ix_attendance_project_approved", columnList = "project_id, approved_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceRecord extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate date;

    @Column(name = "contractor_name", nullable = false, length = 200)
    private String contractorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "skill_category", nullable = false, length = 20)
    private SkillCategory skillCategory;

    @Column(name = "planned_count", nullable = false)
    private Integer plannedCount;

    @Column(name = "actual_count", nullable = false)
    private Integer actualCount;

    @Column(name = "hours_worked", nullable = false, precision = 10, scale = 2)
    private BigDecimal hoursWorked;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;
}
