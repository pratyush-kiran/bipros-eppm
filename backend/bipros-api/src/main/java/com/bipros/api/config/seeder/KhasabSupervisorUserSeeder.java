package com.bipros.api.config.seeder;

import com.bipros.security.domain.model.AuthMethod;
import com.bipros.security.domain.model.Role;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.model.UserRole;
import com.bipros.security.domain.repository.RoleRepository;
import com.bipros.security.domain.repository.UserRepository;
import com.bipros.security.domain.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;

/**
 * Seeds the 12 supervisor {@link User} accounts that appear in the Khasab–Daba (SC-180)
 * customer DPR workbook. Runs at {@code @Order(179)} so the user rows exist before the DPR
 * seeder ({@link KhasabDailyDataSeeder}, {@code @Order(180)}) tries to resolve the
 * {@code supervisorUserId} soft-FK on each DPR row.
 *
 * <p>Idempotent: skips any name whose username is already present (re-runs are safe). Each
 * account gets the {@code SUPERVISOR} role so the standard role-filtered user picker on the
 * frontend (which queries {@code /v1/users?roles=SUPERVISOR}) surfaces them.
 *
 * <p>Profile-gated to {@code seed} only — never runs in prod.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(179)
public class KhasabSupervisorUserSeeder implements CommandLineRunner {

    /** Canonical supervisor names exactly as they appear in the customer workbook. */
    private static final List<String> SUPERVISOR_NAMES = List.of(
            "K. Barman",
            "Sohail",
            "Illayaraja",
            "Parvaiz",
            "Manzar",
            "Mohd Ismaila",
            "Vijaykumar",
            "Md Saiffuddin",
            "V.P. Gupta",
            "A.K. Mishra",
            "Sanjar Alam",
            "Anirban Datta"
    );

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        Role supervisorRole = roleRepository.findByName("SUPERVISOR").orElse(null);
        if (supervisorRole == null) {
            log.warn("[Khasab supervisors] SUPERVISOR role missing; skipping seeding");
            return;
        }

        int created = 0;
        int skipped = 0;
        String hashed = passwordEncoder.encode("Khasab@123");

        for (String fullName : SUPERVISOR_NAMES) {
            String username = toUsername(fullName);
            if (userRepository.existsByUsername(username)) {
                skipped++;
                continue;
            }
            String email = username + "@khasab.bipros.demo";
            if (userRepository.existsByEmail(email)) {
                skipped++;
                continue;
            }

            User u = new User(username, email, hashed);
            String[] parts = splitName(fullName);
            u.setFirstName(parts[0]);
            u.setLastName(parts[1]);
            u.setDesignation("Site Supervisor");
            u.setPrimaryIcpmsRole("Supervisor — SC-180 Khasab–Daba");
            u.setAuthMethods(EnumSet.of(AuthMethod.USERNAME_PASSWORD));
            u.setEnabled(true);
            try {
                u = userRepository.save(u);
                // The @OneToMany on User has no cascade — persist the join row directly,
                // otherwise the SUPERVISOR role won't appear in JWT claims or role filters.
                userRoleRepository.save(new UserRole(u.getId(), supervisorRole.getId()));
                created++;
            } catch (Exception e) {
                log.warn("[Khasab supervisors] failed to create '{}' ({}): {}",
                        fullName, username, e.getMessage());
            }
        }

        log.info("[Khasab supervisors] created {} users; skipped {} (already present)",
                created, skipped);
    }

    /** Stable, deterministic username derived from a display name. */
    static String toUsername(String fullName) {
        return fullName.toLowerCase().replaceAll("[^a-z0-9]+", ".")
                .replaceAll("^\\.+|\\.+$", "");
    }

    private static String[] splitName(String fullName) {
        String trimmed = fullName.trim();
        int sp = trimmed.lastIndexOf(' ');
        if (sp <= 0) return new String[]{trimmed, ""};
        return new String[]{trimmed.substring(0, sp).trim(), trimmed.substring(sp + 1).trim()};
    }
}
