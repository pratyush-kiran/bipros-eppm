package com.bipros.hds.domain.repo;

import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HdsVersionRepository extends JpaRepository<HdsVersion, UUID> {
    List<HdsVersion> findByHdsDocumentIdOrderByRevisionYearDesc(UUID hdsDocumentId);
    List<HdsVersion> findByStatusOrderByUploadedAtAsc(HdsVersionStatus status);
    List<HdsVersion> findByStatus(HdsVersionStatus status);
    Optional<HdsVersion> findByFileSha256(String sha256);
}
