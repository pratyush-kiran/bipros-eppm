package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.QcOutcome;
import com.bipros.project.domain.model.QcSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QcSessionRepository extends JpaRepository<QcSession, UUID> {

    @Query("""
        SELECT DISTINCT s FROM QcSession s
        LEFT JOIN FETCH s.items i
        WHERE s.projectId = :projectId
        AND (:activityId IS NULL OR s.activityId = :activityId)
        AND (:outcome IS NULL OR i.outcome = :outcome)
        AND (:fromDate IS NULL OR s.testDate >= :fromDate)
        AND (:toDate IS NULL OR s.testDate <= :toDate)
        ORDER BY s.testDate DESC, s.createdAt DESC
        """)
    List<QcSession> findAllByProjectIdAndFilters(
        @Param("projectId") UUID projectId,
        @Param("activityId") UUID activityId,
        @Param("outcome") QcOutcome outcome,
        @Param("fromDate") LocalDate fromDate,
        @Param("toDate") LocalDate toDate);

    @Query("SELECT s FROM QcSession s LEFT JOIN FETCH s.items WHERE s.id = :id AND s.projectId = :projectId")
    Optional<QcSession> findByIdAndProjectId(@Param("id") UUID id, @Param("projectId") UUID projectId);

    @Query("""
        SELECT s.activityId, s.activityName, i.outcome, COUNT(i)
        FROM QcSession s JOIN s.items i
        WHERE s.projectId = :projectId
        GROUP BY s.activityId, s.activityName, i.outcome
        ORDER BY s.activityName
        """)
    List<Object[]> groupByActivityAndOutcome(@Param("projectId") UUID projectId);

    @Query("SELECT COUNT(i) FROM QcSession s JOIN s.items i WHERE s.projectId = :projectId AND i.outcome = :outcome")
    long countItemsByProjectIdAndOutcome(@Param("projectId") UUID projectId, @Param("outcome") QcOutcome outcome);

    @Query("""
        SELECT i FROM QcSession s JOIN s.items i
        WHERE s.projectId = :projectId AND i.outcome = 'FAIL'
        ORDER BY s.testDate DESC, i.createdAt DESC
        """)
    List<Object> findRecentFailItemsByProjectId(@Param("projectId") UUID projectId, Pageable pageable);

    @Query("""
        SELECT COUNT(i) FROM QcSession s JOIN s.items i
        WHERE s.projectId = :projectId AND s.testDate = :today
        """)
    long countTodayItems(@Param("projectId") UUID projectId, @Param("today") LocalDate today);
}
