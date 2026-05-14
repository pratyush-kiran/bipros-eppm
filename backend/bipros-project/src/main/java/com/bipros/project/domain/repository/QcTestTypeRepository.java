package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.QcTestType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QcTestTypeRepository extends JpaRepository<QcTestType, UUID> {

    List<QcTestType> findAllByProjectIdAndActiveTrue(UUID projectId);

    Optional<QcTestType> findByIdAndProjectId(UUID id, UUID projectId);
}
