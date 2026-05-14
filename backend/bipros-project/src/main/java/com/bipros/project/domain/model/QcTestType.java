package com.bipros.project.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Master data: a configurable QC test type with IRC/MORTH threshold, scoped per project.
 */
@Entity
@Table(
    name = "qc_test_types",
    schema = "activity",
    indexes = {
        @Index(name = "idx_qc_test_types_project", columnList = "project_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QcTestType extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "unit", length = 30)
    private String unit;

    @Column(name = "irc_threshold", precision = 19, scale = 4)
    private BigDecimal ircThreshold;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}
