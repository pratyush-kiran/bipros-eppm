package com.bipros.dbs.domain.repository;

import com.bipros.dbs.domain.model.DbsDailyCm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DbsDailyCmRepository extends JpaRepository<DbsDailyCm, UUID> {

    Optional<DbsDailyCm> findByProjectIdAndCmUserIdAndReportDate(
        UUID projectId, UUID cmUserId, LocalDate reportDate);

    List<DbsDailyCm> findByProjectIdAndReportDate(UUID projectId, LocalDate reportDate);

    List<DbsDailyCm> findByProjectIdAndCmUserIdAndReportDateBetween(
        UUID projectId, UUID cmUserId, LocalDate from, LocalDate to);
}
