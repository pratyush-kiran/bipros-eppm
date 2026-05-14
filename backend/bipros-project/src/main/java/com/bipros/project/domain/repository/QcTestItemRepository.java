package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.QcOutcome;
import com.bipros.project.domain.model.QcTestItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface QcTestItemRepository extends JpaRepository<QcTestItem, UUID> {

    @Modifying
    @Query("DELETE FROM QcTestItem i WHERE i.session.id = :sessionId")
    void deleteBySessionId(@Param("sessionId") UUID sessionId);

    @Query("""
        SELECT i FROM QcTestItem i
        JOIN i.session s
        WHERE s.projectId = :projectId AND i.outcome = :outcome
        ORDER BY s.testDate DESC, i.createdAt DESC
        """)
    List<QcTestItem> findByProjectIdAndOutcome(
        @Param("projectId") UUID projectId,
        @Param("outcome") QcOutcome outcome,
        Pageable pageable);
}
