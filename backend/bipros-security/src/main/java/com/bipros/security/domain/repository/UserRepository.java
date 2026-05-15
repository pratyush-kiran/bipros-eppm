package com.bipros.security.domain.repository;

import com.bipros.security.domain.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * Find enabled users that hold ANY of the given role names. Used by the supervisor /
     * staff picker on the frontend, which needs to surface candidates filtered by role
     * (e.g. {@code SUPERVISOR,FOREMAN}). The join goes through the {@code user_roles}
     * bridge (mapped on {@link User#getRoles()}) and uses {@code DISTINCT} so users
     * with two matching roles don't show up twice.
     */
    @Query("SELECT DISTINCT u FROM User u JOIN u.roles ur "
            + "WHERE ur.role.name IN :roleNames AND u.enabled = true")
    Page<User> findByRoleNamesAndEnabled(@Param("roleNames") Collection<String> roleNames,
                                         Pageable pageable);

    /** Non-paginated variant of {@link #findByRoleNamesAndEnabled(Collection, Pageable)}. */
    @Query("SELECT DISTINCT u FROM User u JOIN u.roles ur "
            + "WHERE ur.role.name IN :roleNames AND u.enabled = true")
    List<User> findByRoleNamesAndEnabled(@Param("roleNames") Collection<String> roleNames);
}
