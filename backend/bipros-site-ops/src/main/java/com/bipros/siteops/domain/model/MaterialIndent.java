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
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "material_indents", schema = "site_ops",
        indexes = {
                @Index(name = "ix_material_indent_project_status", columnList = "project_id, status"),
                @Index(name = "ix_material_indent_project_created", columnList = "project_id, created_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_material_indent_project_no", columnNames = {"project_id", "indent_no"})
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MaterialIndent extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "indent_no", nullable = false, length = 40)
    private String indentNo;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "required_by", nullable = false)
    private LocalDate requiredBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private IndentStatus status = IndentStatus.DRAFT;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "decision_by")
    private UUID decisionBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 2000)
    private String decisionNote;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "indent_id", nullable = false)
    @OrderBy("createdAt ASC")
    private List<MaterialIndentItem> items = new ArrayList<>();
}
