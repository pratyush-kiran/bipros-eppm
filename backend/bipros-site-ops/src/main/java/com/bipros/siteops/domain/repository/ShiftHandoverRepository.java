package com.bipros.siteops.domain.repository;

import com.bipros.siteops.domain.model.Shift;
import com.bipros.siteops.domain.model.ShiftHandover;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ShiftHandoverRepository extends JpaRepository<ShiftHandover, UUID> {

    List<ShiftHandover> findByProjectIdOrderByHandedOverAtDesc(UUID projectId);

    List<ShiftHandover> findByProjectIdAndShiftDateOrderByHandedOverAtDesc(UUID projectId, LocalDate shiftDate);

    List<ShiftHandover> findByProjectIdAndShiftOrderByHandedOverAtDesc(UUID projectId, Shift shift);

    List<ShiftHandover> findByProjectIdAndShiftDateAndShiftOrderByHandedOverAtDesc(
            UUID projectId, LocalDate shiftDate, Shift shift);
}
