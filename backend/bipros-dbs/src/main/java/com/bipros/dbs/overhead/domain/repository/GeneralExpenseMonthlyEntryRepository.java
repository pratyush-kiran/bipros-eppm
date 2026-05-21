package com.bipros.dbs.overhead.domain.repository;

import com.bipros.dbs.overhead.domain.model.GeneralExpenseMonthlyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GeneralExpenseMonthlyEntryRepository extends JpaRepository<GeneralExpenseMonthlyEntry, UUID> {

    List<GeneralExpenseMonthlyEntry> findByProjectIdAndYearMonth(UUID projectId, Integer yearMonth);

    Optional<GeneralExpenseMonthlyEntry> findByPlanItemIdAndYearMonth(UUID planItemId, Integer yearMonth);

    @Query("""
        SELECT COALESCE(SUM(e.achievedAmount), 0)
        FROM GeneralExpenseMonthlyEntry e
        WHERE e.projectId = :projectId AND e.yearMonth = :yearMonth
        """)
    BigDecimal sumAchievedAmount(UUID projectId, Integer yearMonth);

    void deleteByProjectId(UUID projectId);
}
