package com.bipros.hds.domain.repo;

import com.bipros.hds.domain.HdsDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface HdsDocumentRepository extends JpaRepository<HdsDocument, UUID> {
    Optional<HdsDocument> findByShortCode(String shortCode);
    boolean existsByShortCode(String shortCode);
}
