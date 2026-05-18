package com.bipros.siteops.application.service;

import com.bipros.common.exception.ConcurrencyException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.security.application.service.CurrentUserService;
import com.bipros.siteops.application.dto.AttendanceResponse;
import com.bipros.siteops.application.dto.AttendanceSummary;
import com.bipros.siteops.application.dto.CreateAttendanceRequest;
import com.bipros.siteops.application.dto.UpdateAttendanceRequest;
import com.bipros.siteops.domain.model.AttendanceRecord;
import com.bipros.siteops.domain.model.SkillCategory;
import com.bipros.siteops.domain.repository.AttendanceRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class AttendanceService {

    private final AttendanceRecordRepository attendanceRepository;
    private final CurrentUserService securityContextHelper;

    public AttendanceResponse create(UUID projectId, CreateAttendanceRequest request) {
        UUID currentUserId = securityContextHelper.getCurrentUserId();

        AttendanceRecord r = new AttendanceRecord();
        r.setProjectId(projectId);
        r.setDate(request.date());
        r.setContractorName(request.contractorName());
        r.setSkillCategory(request.skillCategory());
        r.setPlannedCount(request.plannedCount());
        r.setActualCount(request.actualCount());
        r.setHoursWorked(request.hoursWorked());
        r.setNotes(request.notes());
        r.setSubmittedBy(currentUserId);
        r.setSubmittedAt(Instant.now());
        return toResponse(attendanceRepository.save(r));
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> list(UUID projectId, LocalDate from, LocalDate to) {
        List<AttendanceRecord> rows = (from == null && to == null)
                ? attendanceRepository.findByProjectIdOrderByDateDescContractorNameAsc(projectId)
                : attendanceRepository.findInRange(projectId, from, to);
        return rows.stream().map(this::toResponse).toList();
    }

    public AttendanceResponse update(UUID projectId, UUID id, UpdateAttendanceRequest request) {
        AttendanceRecord r = loadScoped(projectId, id);
        if (r.getApprovedAt() != null) {
            throw new ConcurrencyException("AttendanceRecord", id);
        }
        if (request.date() != null) r.setDate(request.date());
        if (request.contractorName() != null) r.setContractorName(request.contractorName());
        if (request.skillCategory() != null) r.setSkillCategory(request.skillCategory());
        if (request.plannedCount() != null) r.setPlannedCount(request.plannedCount());
        if (request.actualCount() != null) r.setActualCount(request.actualCount());
        if (request.hoursWorked() != null) r.setHoursWorked(request.hoursWorked());
        if (request.notes() != null) r.setNotes(request.notes());
        return toResponse(attendanceRepository.save(r));
    }

    public AttendanceResponse approve(UUID projectId, UUID id) {
        AttendanceRecord r = loadScoped(projectId, id);
        UUID currentUserId = securityContextHelper.getCurrentUserId();
        if (r.getApprovedAt() != null) {
            return toResponse(r);
        }
        r.setApprovedBy(currentUserId);
        r.setApprovedAt(Instant.now());
        return toResponse(attendanceRepository.save(r));
    }

    @Transactional(readOnly = true)
    public List<AttendanceSummary> summary(UUID projectId, LocalDate from, LocalDate to) {
        List<AttendanceRecord> rows = (from == null && to == null)
                ? attendanceRepository.findByProjectIdOrderByDateDescContractorNameAsc(projectId)
                : attendanceRepository.findInRange(projectId, from, to);

        Map<SkillCategory, long[]> counts = new HashMap<>();
        Map<SkillCategory, BigDecimal> hours = new HashMap<>();
        for (AttendanceRecord r : rows) {
            long[] c = counts.computeIfAbsent(r.getSkillCategory(), k -> new long[3]); // planned, actual, rowCount
            c[0] += r.getPlannedCount();
            c[1] += r.getActualCount();
            c[2] += 1;
            hours.merge(r.getSkillCategory(), r.getHoursWorked(), BigDecimal::add);
        }
        return counts.entrySet().stream()
                .map(e -> new AttendanceSummary(
                        e.getKey(),
                        e.getValue()[0],
                        e.getValue()[1],
                        hours.getOrDefault(e.getKey(), BigDecimal.ZERO),
                        e.getValue()[2]))
                .toList();
    }

    private AttendanceRecord loadScoped(UUID projectId, UUID id) {
        AttendanceRecord r = attendanceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AttendanceRecord", id));
        if (!r.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("AttendanceRecord", id);
        }
        return r;
    }

    private AttendanceResponse toResponse(AttendanceRecord r) {
        return new AttendanceResponse(
                r.getId(),
                r.getProjectId(),
                r.getDate(),
                r.getContractorName(),
                r.getSkillCategory(),
                r.getPlannedCount(),
                r.getActualCount(),
                r.getHoursWorked(),
                r.getNotes(),
                r.getApprovedBy(),
                r.getApprovedAt(),
                r.getSubmittedBy(),
                r.getSubmittedAt(),
                r.getCreatedAt(),
                r.getCreatedBy(),
                r.getUpdatedAt(),
                r.getUpdatedBy()
        );
    }
}
