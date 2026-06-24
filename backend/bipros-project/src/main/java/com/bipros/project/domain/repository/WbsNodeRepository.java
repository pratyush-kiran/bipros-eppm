package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.WbsNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WbsNodeRepository extends JpaRepository<WbsNode, UUID> {

    List<WbsNode> findByProjectIdAndParentIdIsNullOrderBySortOrder(UUID projectId);

    List<WbsNode> findByParentIdOrderBySortOrder(UUID parentId);

    List<WbsNode> findByProjectIdOrderBySortOrder(UUID projectId);

    Optional<WbsNode> findByCode(String code);

    Optional<WbsNode> findByProjectIdAndCode(UUID projectId, String code);

    boolean existsByCode(String code);

    boolean existsByProjectIdAndCode(UUID projectId, String code);

    /** Number of WBS elements assigned to an OBS node — used to guard OBS deletion. */
    long countByObsNodeId(UUID obsNodeId);
}
