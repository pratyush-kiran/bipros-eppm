package com.bipros.resource.domain.repository;

import com.bipros.resource.domain.model.ActivityExpenseBudget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActivityExpenseBudgetRepository
    extends JpaRepository<ActivityExpenseBudget, UUID> {

  List<ActivityExpenseBudget> findByActivityId(UUID activityId);

  List<ActivityExpenseBudget> findByProjectId(UUID projectId);

  Optional<ActivityExpenseBudget> findByActivityIdAndCategoryId(UUID activityId, UUID categoryId);
}
