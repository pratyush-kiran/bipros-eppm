package com.bipros.hds.domain;

import com.bipros.common.model.BaseEntity;
import com.bipros.hds.domain.enums.HdsDiscipline;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "hds_document", schema = "hds",
       uniqueConstraints = @UniqueConstraint(name = "uk_hds_document_short_code", columnNames = "short_code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Builder
public class HdsDocument extends BaseEntity {

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "short_code", nullable = false, length = 32)
    private String shortCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "discipline", nullable = false, length = 32)
    private HdsDiscipline discipline;

    @Column(name = "issuing_authority", length = 255)
    private String issuingAuthority;

    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "description", columnDefinition = "text")
    private String description;
}
