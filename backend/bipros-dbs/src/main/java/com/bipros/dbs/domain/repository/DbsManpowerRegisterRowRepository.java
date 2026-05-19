package com.bipros.dbs.domain.repository;

import com.bipros.dbs.domain.model.DbsManpowerRegisterRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface DbsManpowerRegisterRowRepository extends JpaRepository<DbsManpowerRegisterRow, UUID> {

    List<DbsManpowerRegisterRow> findByProjectIdAndReportDate(UUID projectId, LocalDate reportDate);

    List<DbsManpowerRegisterRow> findByProjectIdAndReportDateAndCmUserId(
        UUID projectId, LocalDate reportDate, UUID cmUserId);

    @Modifying
    @Query("DELETE FROM DbsManpowerRegisterRow r WHERE r.projectId = :projectId AND r.reportDate = :date")
    int deleteByProjectIdAndReportDate(@Param("projectId") UUID projectId, @Param("date") LocalDate date);
}
