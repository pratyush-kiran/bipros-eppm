package com.bipros.activity.domain.repository;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.PercentCompleteType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, UUID>, JpaSpecificationExecutor<Activity> {
  List<Activity> findByProjectId(UUID projectId);

  List<Activity> findByProjectIdIn(List<UUID> projectIds);

  List<Activity> findByWbsNodeId(UUID wbsNodeId);

  List<Activity> findByProjectIdAndIsCritical(UUID projectId, Boolean isCritical);

  /**
   * Phase 4.5: the legacy {@code responsible_resource_id} column was dropped by Liquibase 094.
   * Old AI/graph callers still ask "what activities does this resource supervise?" with a
   * Resource id, so this query bridges via {@code resource.resources.user_id} → the canonical
   * {@code activities.supervisor_user_id}. When the Resource has no linked user (or the user
   * supervises no activities) the result is empty — matches the legacy semantics.
   *
   * @deprecated New callers should look up by {@code supervisorUserId} directly.
   */
  @Deprecated(forRemoval = true)
  @Query(value = """
      SELECT a.* FROM activity.activities a
      WHERE a.project_id = :projectId
        AND a.supervisor_user_id = (
          SELECT r.user_id FROM resource.resources r WHERE r.id = :responsibleResourceId
        )
      """, nativeQuery = true)
  List<Activity> findByProjectIdAndResponsibleResourceId(
      @Param("projectId") UUID projectId,
      @Param("responsibleResourceId") UUID responsibleResourceId);

  long countByProjectId(UUID projectId);

  long countByWbsNodeId(UUID wbsNodeId);

  long countByCalendarId(UUID calendarId);

  Page<Activity> findByProjectIdOrderBySortOrder(UUID projectId, Pageable pageable);

  List<Activity> findByIdIn(List<UUID> ids);

  List<Activity> findByPercentCompleteTypeAndStatusIn(PercentCompleteType percentCompleteType, List<ActivityStatus> statuses);

  boolean existsByProjectIdAndCode(UUID projectId, String code);

  Optional<Activity> findByProjectIdAndCode(UUID projectId, String code);
}
