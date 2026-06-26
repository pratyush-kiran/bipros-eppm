package com.bipros.project.domain.repository;

import com.bipros.project.domain.model.DprApprovalHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DprApprovalHistoryRepository extends JpaRepository<DprApprovalHistory, UUID> {

    List<DprApprovalHistory> findByDprIdOrderByCreatedAtAsc(UUID dprId);
}
