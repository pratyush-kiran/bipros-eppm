package com.bipros.cost.domain.repository;

import com.bipros.cost.domain.entity.FinancialPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FinancialPeriodRepository extends JpaRepository<FinancialPeriod, UUID> {

    /** All financial periods owned by the given project, oldest first. */
    List<FinancialPeriod> findByProjectIdOrderBySortOrderAsc(UUID projectId);

    /** Open (not yet closed) financial periods for the given project, oldest first. */
    List<FinancialPeriod> findByProjectIdAndIsClosedFalseOrderBySortOrderAsc(UUID projectId);
}
