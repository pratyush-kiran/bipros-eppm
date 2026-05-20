package com.bipros.resource.domain.repository;

import com.bipros.resource.domain.model.ExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {

  Optional<ExpenseCategory> findByCode(String code);

  List<ExpenseCategory> findByActiveTrue();

  List<ExpenseCategory> findByDbsSectionAndActiveTrue(String dbsSection);
}
