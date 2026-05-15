package com.bipros.siteops.domain.repository;

import com.bipros.siteops.domain.model.ChecklistTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChecklistTemplateRepository extends JpaRepository<ChecklistTemplate, UUID> {

    Optional<ChecklistTemplate> findByCode(String code);

    List<ChecklistTemplate> findByActiveTrueOrderByNameAsc();
}
