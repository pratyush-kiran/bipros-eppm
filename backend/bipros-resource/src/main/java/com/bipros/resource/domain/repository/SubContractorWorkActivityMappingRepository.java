package com.bipros.resource.domain.repository;

import com.bipros.resource.domain.model.SubContractorWorkActivityMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SubContractorWorkActivityMappingRepository
    extends JpaRepository<SubContractorWorkActivityMapping, UUID> {

  List<SubContractorWorkActivityMapping>
      findBySubContractorMasterIdOrderByWorkTypeNameAsc(UUID subContractorMasterId);

  void deleteBySubContractorMasterId(UUID subContractorMasterId);

  boolean existsBySubContractorMasterIdAndScWorkTypeId(
      UUID subContractorMasterId, UUID scWorkTypeId);

  java.util.Optional<SubContractorWorkActivityMapping>
      findBySubContractorMasterIdAndScWorkTypeId(
          UUID subContractorMasterId, UUID scWorkTypeId);
}
