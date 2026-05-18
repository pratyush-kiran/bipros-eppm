package com.bipros.resource.domain.repository;

import com.bipros.resource.domain.model.LabourCategory;
import com.bipros.resource.domain.model.LabourDesignation;
import com.bipros.resource.domain.model.LabourGrade;
import com.bipros.resource.domain.model.LabourStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LabourDesignationRepository extends JpaRepository<LabourDesignation, UUID> {

    Optional<LabourDesignation> findByCode(String code);

    boolean existsByCode(String code);

    List<LabourDesignation> findAllByOrderBySortOrderAscCodeAsc();

    @Query("""
        SELECT d FROM LabourDesignation d
        WHERE (:category IS NULL OR d.category = :category)
          AND (:grade    IS NULL OR d.grade    = :grade)
          AND (:status   IS NULL OR d.status   = :status)
          AND (:q = '' OR LOWER(d.code) LIKE LOWER(CONCAT('%', :q, '%'))
                       OR LOWER(d.designation) LIKE LOWER(CONCAT('%', :q, '%'))
                       OR LOWER(d.trade) LIKE LOWER(CONCAT('%', :q, '%')))
        """)
    Page<LabourDesignation> search(@Param("category") LabourCategory category,
                                   @Param("grade")    LabourGrade grade,
                                   @Param("status")   LabourStatus status,
                                   @Param("q")        String q,
                                   Pageable pageable);
}
