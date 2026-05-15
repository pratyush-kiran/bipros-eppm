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
@Table(name = "checklist_answers", schema = "site_ops", indexes = {
        @Index(name = "ix_checklist_answer_instance_item", columnList = "instance_id, item_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChecklistAnswer extends BaseEntity {

    @Column(name = "instance_id", nullable = false, insertable = false, updatable = false)
    private UUID instanceId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "value", length = 10)
    private AnswerValue value;

    @Column(name = "note", length = 2000)
    private String note;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;
}
