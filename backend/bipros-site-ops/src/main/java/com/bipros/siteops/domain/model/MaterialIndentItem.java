package com.bipros.siteops.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "material_indent_items", schema = "site_ops", indexes = {
        @Index(name = "ix_material_indent_item_indent", columnList = "indent_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MaterialIndentItem extends BaseEntity {

    @Column(name = "indent_id", nullable = false, insertable = false, updatable = false)
    private UUID indentId;

    @Column(name = "material_name", nullable = false, length = 200)
    private String materialName;

    @Column(name = "quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(name = "uom", nullable = false, length = 30)
    private String uom;

    @Column(name = "remarks", length = 1000)
    private String remarks;
}
