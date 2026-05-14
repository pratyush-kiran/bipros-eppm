package com.bipros.project.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One test result row within a {@link QcSession}. Soft FK to session — lifecycle managed by
 * QcSessionService with full-replacement semantics on update.
 */
@Entity
@Table(
    name = "qc_test_items",
    schema = "activity",
    indexes = {
        @Index(name = "idx_qc_test_items_session", columnList = "session_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QcTestItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private QcSession session;

    /** Soft FK to activity.qc_test_types.id. */
    @Column(name = "test_type_id", nullable = false)
    private UUID testTypeId;

    /** Snapshot of test type name at create time. */
    @Column(name = "test_type_name", nullable = false, length = 100)
    private String testTypeName;

    @Column(name = "sample_ref_no", length = 50)
    private String sampleRefNo;

    @Column(name = "test_result", precision = 19, scale = 4)
    private BigDecimal testResult;

    /** Auto-filled from QcTestType.ircThreshold; editable per item. */
    @Column(name = "required_irc", precision = 19, scale = 4)
    private BigDecimal requiredIrc;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 10)
    private QcOutcome outcome;

    @Column(name = "lab_inspector", length = 150)
    private String labInspector;
}
