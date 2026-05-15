package com.bipros.resource.domain.repository;

import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResourceRepository extends JpaRepository<Resource, UUID> {

  Optional<Resource> findByCode(String code);

  List<Resource> findByResourceType_Code(String typeCode);

  List<Resource> findByResourceType_Id(UUID typeId);

  List<Resource> findByRole_Id(UUID roleId);

  List<Resource> findByParentIdIsNull();

  List<Resource> findByParentId(UUID parentId);

  List<Resource> findByStatus(ResourceStatus status);

  long countByResourceType_Id(UUID typeId);

  long countByRole_Id(UUID roleId);

  /**
   * Resources linked to a given rate master row. Used by {@code RateMasterSyncService} to
   * cascade unit + rate edits to all linked resources in the same transaction.
   */
  List<Resource> findByRateMasterId(UUID rateMasterId);

  /**
   * Active resources of a given type. Used by the Phase 7.2 eligible-supervisors endpoint
   * that powers the DPR form's Supervisor dropdown.
   *
   * <p>Originally the filter restricted by role.code containing SUPERVISOR/FOREMAN, but role
   * codes vary widely by seeder/customer (FOREMAN, BNK-ROLE-SUPERVISOR, "Site Supervisor")
   * and the structured filter regularly produced empty dropdowns even on projects with valid
   * field staff. The dropdown now shows every active Labor resource — the role name is
   * still surfaced in each option label so users can pick supervisor-class workers easily.
   * Project-scoping (allocated resources first, with global fallback) is enforced in the
   * controller, not here.
   */
  List<Resource> findByResourceType_CodeAndStatus(String typeCode, ResourceStatus status);
}
