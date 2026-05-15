package com.bipros.siteops.domain.repository;

import com.bipros.siteops.domain.model.SafetyKind;
import com.bipros.siteops.domain.model.SafetyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SafetyRecordRepository extends JpaRepository<SafetyRecord, UUID> {

    List<SafetyRecord> findByProjectIdOrderByOccurredAtDesc(UUID projectId);

    List<SafetyRecord> findByProjectIdAndKindOrderByOccurredAtDesc(UUID projectId, SafetyKind kind);
}
