package com.bipros.dbs.domain.repository;

import com.bipros.dbs.domain.model.DbsDailyProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DbsDailyProjectRepository extends JpaRepository<DbsDailyProject, UUID> {

    Optional<DbsDailyProject> findByProjectIdAndReportDate(UUID projectId, LocalDate reportDate);

    List<DbsDailyProject> findByProjectIdAndReportDateBetween(
        UUID projectId, LocalDate from, LocalDate to);
}
