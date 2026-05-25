package com.bipros.resource.domain.repository;

import com.bipros.resource.domain.model.master.SubContractorMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubContractorMasterRepository
    extends JpaRepository<SubContractorMaster, UUID> {

  Optional<SubContractorMaster> findByCode(String code);

  Optional<SubContractorMaster> findByName(String name);
}
