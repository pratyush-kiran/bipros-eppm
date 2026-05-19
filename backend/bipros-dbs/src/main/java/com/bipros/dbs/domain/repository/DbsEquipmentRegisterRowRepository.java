package com.bipros.dbs.domain.repository;

import com.bipros.dbs.domain.model.DbsEquipmentRegisterRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface DbsEquipmentRegisterRowRepository extends JpaRepository<DbsEquipmentRegisterRow, UUID> {

    List<DbsEquipmentRegisterRow> findByProjectIdAndReportDate(UUID projectId, LocalDate reportDate);

    List<DbsEquipmentRegisterRow> findByProjectIdAndReportDateAndCmUserId(
        UUID projectId, LocalDate reportDate, UUID cmUserId);

    @Modifying
    @Query("DELETE FROM DbsEquipmentRegisterRow r WHERE r.projectId = :projectId AND r.reportDate = :date")
    int deleteByProjectIdAndReportDate(@Param("projectId") UUID projectId, @Param("date") LocalDate date);
}
