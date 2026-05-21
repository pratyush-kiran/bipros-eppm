package com.bipros.dbs.overhead.domain.repository;

import com.bipros.dbs.overhead.domain.model.GeneralExpensePlanItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GeneralExpensePlanItemRepository extends JpaRepository<GeneralExpensePlanItem, UUID> {

    List<GeneralExpensePlanItem> findByProjectIdOrderBySortOrderAsc(UUID projectId);

    long countByProjectId(UUID projectId);
}
