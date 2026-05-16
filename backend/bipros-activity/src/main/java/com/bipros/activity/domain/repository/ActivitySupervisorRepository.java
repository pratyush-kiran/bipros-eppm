package com.bipros.activity.domain.repository;

import com.bipros.activity.domain.model.ActivitySupervisor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ActivitySupervisorRepository extends JpaRepository<ActivitySupervisor, UUID> {

  List<ActivitySupervisor> findByActivityId(UUID activityId);

  List<ActivitySupervisor> findByActivityIdIn(Collection<UUID> activityIds);

  List<ActivitySupervisor> findByUserId(UUID userId);

  boolean existsByActivityIdAndUserId(UUID activityId, UUID userId);

  @Modifying
  void deleteByActivityId(UUID activityId);

  @Modifying
  void deleteByActivityIdAndUserId(UUID activityId, UUID userId);

  @Modifying
  long deleteByUserId(UUID userId);

  /**
   * Drop rows whose {@code user_id} no longer points to a row in {@code public.users}.
   * The FK is soft (no DB cascade) so deleting a User leaves these orphans behind —
   * they cause "ghost supervisor" entries that the AI then has to fabricate identities
   * for. Native query because we cross-schema reference {@code public.users}.
   */
  @Modifying
  @Query(value = "DELETE FROM activity.activity_supervisors s "
      + "WHERE NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = s.user_id)",
      nativeQuery = true)
  int deleteOrphanRows();

  /**
   * Distinct project ids of activities the given user supervises. Joins through
   * {@link com.bipros.activity.domain.model.Activity} to read {@code projectId}.
   * Replaces the single-column {@code findDistinctProjectIdsBySupervisorUserId}
   * on {@link ActivityRepository}, which only saw the legacy first-supervisor cache.
   */
  @Query("""
      SELECT DISTINCT a.projectId
      FROM com.bipros.activity.domain.model.Activity a,
           com.bipros.activity.domain.model.ActivitySupervisor s
      WHERE s.activityId = a.id
        AND s.userId = :userId
      """)
  List<UUID> findDistinctProjectIdsByUserId(@Param("userId") UUID userId);
}
