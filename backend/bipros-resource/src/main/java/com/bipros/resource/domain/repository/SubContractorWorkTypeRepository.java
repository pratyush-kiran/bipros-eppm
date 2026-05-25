package com.bipros.resource.domain.repository;

import com.bipros.resource.domain.model.SubContractorWorkType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubContractorWorkTypeRepository
    extends JpaRepository<SubContractorWorkType, UUID> {

  Optional<SubContractorWorkType> findByNameIgnoreCase(String name);

  List<SubContractorWorkType> findTop20ByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(
      String query);

  List<SubContractorWorkType> findAllByActiveTrueOrderByNameAsc();
}
