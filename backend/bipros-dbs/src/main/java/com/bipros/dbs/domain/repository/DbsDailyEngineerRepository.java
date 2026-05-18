package com.bipros.dbs.domain.repository;

import com.bipros.dbs.domain.model.DbsDailyEngineer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DbsDailyEngineerRepository extends JpaRepository<DbsDailyEngineer, UUID> {

    Optional<DbsDailyEngineer> findByProjectIdAndEngineerUserIdAndReportDate(
        UUID projectId, UUID engineerUserId, LocalDate reportDate);

    Optional<DbsDailyEngineer> findByProjectIdAndReportDateAndEngineerUserIdIsNull(
        UUID projectId, LocalDate reportDate);

    List<DbsDailyEngineer> findByProjectIdAndReportDate(UUID projectId, LocalDate reportDate);

    List<DbsDailyEngineer> findByProjectIdAndReportDateBetween(
        UUID projectId, LocalDate from, LocalDate to);
}
