package com.bipros.hds.domain.repo;

import com.bipros.hds.domain.HdsQueryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface HdsQueryLogRepository extends JpaRepository<HdsQueryLog, UUID> {}
