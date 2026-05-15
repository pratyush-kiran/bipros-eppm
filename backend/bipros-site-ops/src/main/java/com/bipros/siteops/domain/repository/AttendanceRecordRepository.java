package com.bipros.siteops.domain.repository;

import com.bipros.siteops.domain.model.AttendanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {

    List<AttendanceRecord> findByProjectIdOrderByDateDescContractorNameAsc(UUID projectId);

    @Query("select a from AttendanceRecord a where a.projectId = :projectId " +
            "and (:from is null or a.date >= :from) " +
            "and (:to is null or a.date <= :to) " +
            "order by a.date desc, a.contractorName asc")
    List<AttendanceRecord> findInRange(
            @Param("projectId") UUID projectId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
