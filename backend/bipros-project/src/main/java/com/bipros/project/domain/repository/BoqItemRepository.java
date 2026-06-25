package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.BoqItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BoqItemRepository extends JpaRepository<BoqItem, UUID> {

  List<BoqItem> findByProjectId(UUID projectId);

  List<BoqItem> findByProjectIdOrderByItemNoAsc(UUID projectId);

  Optional<BoqItem> findByProjectIdAndItemNo(UUID projectId, String itemNo);

  boolean existsByProjectIdAndItemNo(UUID projectId, String itemNo);
}
