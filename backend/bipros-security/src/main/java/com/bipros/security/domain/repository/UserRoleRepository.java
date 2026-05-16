package com.bipros.security.domain.repository;

import com.bipros.security.domain.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

    boolean existsByUserIdAndRoleId(UUID userId, UUID roleId);

    @Modifying
    long deleteByUserId(UUID userId);
}
