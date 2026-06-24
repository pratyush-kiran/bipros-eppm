package com.bipros.scheduling.domain.repository;

import com.bipros.scheduling.domain.model.ScheduleResult;
import com.bipros.scheduling.domain.model.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScheduleResultRepository extends JpaRepository<ScheduleResult, UUID> {

  Optional<ScheduleResult> findTopByProjectIdOrderByCalculatedAtDesc(UUID projectId);

  Optional<ScheduleResult> findTopByProjectIdAndStatusOrderByCalculatedAtDesc(UUID projectId, ScheduleStatus status);

  List<ScheduleResult> findByProjectId(UUID projectId);
}
