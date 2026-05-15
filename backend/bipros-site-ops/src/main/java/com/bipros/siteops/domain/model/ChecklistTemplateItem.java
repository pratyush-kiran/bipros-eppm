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

import java.util.UUID;

@Entity
@Table(name = "checklist_template_items", schema = "site_ops", indexes = {
        @Index(name = "ix_checklist_template_item_template", columnList = "template_id, sequence")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChecklistTemplateItem extends BaseEntity {

    @Column(name = "template_id", nullable = false, insertable = false, updatable = false)
    private UUID templateId;

    @Column(name = "sequence", nullable = false)
    private Integer sequence;

    @Column(name = "label", nullable = false, length = 500)
    private String label;

    @Column(name = "mandatory", nullable = false)
    private boolean mandatory = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false, length = 20)
    private EvidenceType evidenceType = EvidenceType.NONE;
}
