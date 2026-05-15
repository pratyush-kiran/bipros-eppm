package com.bipros.security.domain.repository;

import com.bipros.security.domain.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByName(String name);

    boolean existsByName(String name);

    /**
     * Returns one row per role with {@code [id, name, description, memberCount]}.
     * {@code memberCount} is the number of <em>enabled</em> users currently
     * holding that role — locked / disabled users are excluded. Ordered by name
     * so the UI can render the catalog in a stable alphabetical sequence.
     *
     * <p>Uses a correlated subquery (rather than {@code LEFT JOIN ... GROUP BY})
     * so roles with zero members still appear in the result set.
     */
    @Query("SELECT r.id, r.name, r.description, " +
           "       (SELECT COUNT(ur) FROM UserRole ur " +
           "          WHERE ur.role.id = r.id AND ur.user.enabled = true) " +
           "FROM SecurityRole r ORDER BY r.name")
    List<Object[]> findAllWithMemberCount();
}
