package com.bipros.activity.domain.repository;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.PercentCompleteType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
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
   * Multi-supervisor era: bridge a Resource id to the activities supervised by the
   * Resource's linked user. Joins {@code activity.activity_supervisors} so that an
   * activity is returned if the bridged user is ANY of its supervisors (not only the
   * legacy single {@code supervisor_user_id} cache).
   *
   * @deprecated New callers should resolve to a User id and use
   *     {@link com.bipros.activity.domain.repository.ActivitySupervisorRepository#findByUserId}
   *     scoped to the project.
   */
  @Deprecated(forRemoval = true)
  @Query(value = """
      SELECT DISTINCT a.* FROM activity.activities a
      JOIN activity.activity_supervisors s ON s.activity_id = a.id
      WHERE a.project_id = :projectId
        AND s.user_id = (
          SELECT r.user_id FROM resource.resources r WHERE r.id = :responsibleResourceId
        )
      """, nativeQuery = true)
  List<Activity> findByProjectIdAndResponsibleResourceId(
      @Param("projectId") UUID projectId,
      @Param("responsibleResourceId") UUID responsibleResourceId);

  /**
   * Distinct project ids of activities this user supervises. Used by the AI
   * EntityResolver to attach a {@code projects} array to supervisor candidates
   * resolved from the User table — so portfolio-mode chat queries like
   * "performance of Vijaykumar" can auto-adopt the right project. Multi-supervisor
   * era: joins {@code ActivitySupervisor} so co-supervised activities are also
   * counted.
   */
  @Query("""
      SELECT DISTINCT a.projectId
      FROM Activity a, com.bipros.activity.domain.model.ActivitySupervisor s
      WHERE s.activityId = a.id
        AND s.userId = :supervisorUserId
      """)
  List<UUID> findDistinctProjectIdsBySupervisorUserId(@Param("supervisorUserId") UUID supervisorUserId);

  long countByProjectId(UUID projectId);

  long countByWbsNodeId(UUID wbsNodeId);

  long countByCalendarId(UUID calendarId);

  Page<Activity> findByProjectIdOrderBySortOrder(UUID projectId, Pageable pageable);

  List<Activity> findByIdIn(List<UUID> ids);

  List<Activity> findByPercentCompleteTypeAndStatusIn(PercentCompleteType percentCompleteType, List<ActivityStatus> statuses);

  boolean existsByProjectIdAndCode(UUID projectId, String code);

  Optional<Activity> findByProjectIdAndCode(UUID projectId, String code);

  /** Clear the legacy single-supervisor cache when the underlying user is deleted. */
  @Modifying
  @Query("UPDATE Activity a SET a.supervisorUserId = null, a.supervisorUserName = null "
      + "WHERE a.supervisorUserId = :userId")
  int detachSupervisor(@Param("userId") UUID userId);
}
