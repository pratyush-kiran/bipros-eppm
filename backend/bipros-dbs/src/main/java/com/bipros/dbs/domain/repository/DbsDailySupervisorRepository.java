package com.bipros.dbs.domain.repository;

import com.bipros.dbs.domain.model.DbsDailySupervisor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DbsDailySupervisorRepository extends JpaRepository<DbsDailySupervisor, UUID> {

    Optional<DbsDailySupervisor> findByProjectIdAndSupervisorUserIdAndReportDate(
        UUID projectId, UUID supervisorUserId, LocalDate reportDate);

    Optional<DbsDailySupervisor> findByProjectIdAndReportDateAndSupervisorUserIdIsNull(
        UUID projectId, LocalDate reportDate);

    List<DbsDailySupervisor> findByProjectIdAndReportDate(UUID projectId, LocalDate reportDate);

    List<DbsDailySupervisor> findByProjectIdAndReportDateBetween(
        UUID projectId, LocalDate from, LocalDate to);

    /**
     * All supervisor rows on a given date whose denormalised
     * {@code construction_manager_user_id} matches the supplied CM. Powers the
     * {@code dbs_daily_cm} rollup recompute.
     */
    List<DbsDailySupervisor> findByProjectIdAndReportDateAndConstructionManagerUserId(
        UUID projectId, LocalDate reportDate, UUID constructionManagerUserId);
}
