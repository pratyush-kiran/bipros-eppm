package com.bipros.api.config.seeder;

import com.bipros.api.config.seeder.OmanDemoWorkbookReader.StaffMasterRow;
import com.bipros.security.domain.model.AuthMethod;
import com.bipros.security.domain.model.Department;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates the OMAN-Demo staff directory (PM / CMs / engineers / supervisors) from the
 * customer-supplied workbooks and registers each resulting user in
 * {@link OmanDemoStaffDirectory} so the downstream seeders (project, WBS, daily data,
 * performance) can resolve display names → user ids deterministically.
 *
 * <p>Idempotent: existing usernames are skipped; the directory is rebuilt from
 * {@link UserRepository} on re-run so re-seeds still expose the full mapping to later
 * seeders.
 *
 * <p>Usernames are <b>employee-code-style</b> ({@code EMP-001}, {@code EMP-101}, ...) so
 * the slug never leaks into AI prompts as a separate identity. The display name on the
 * row is the exact workbook spelling (e.g. {@code "Mohd Ismaila"}, {@code "K. Barman"}).
 *
 * <p>EMP code allocation (deterministic, follows {@code readStaffMaster()} order). The
 * range starts at 200 to avoid colliding with the platform's pre-seeded admin / e2e /
 * developer accounts that already occupy {@code EMP-001..EMP-008}:
 * <ul>
 *   <li>{@code PM} → {@code EMP-200}</li>
 *   <li>{@code CM} → {@code EMP-201..EMP-209}</li>
 *   <li>{@code ENGINEER} → {@code EMP-210..EMP-299}</li>
 *   <li>{@code SUPERVISOR} → {@code EMP-300..}</li>
 * </ul>
 *
 * <p>Profile-gated to {@code seed}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(200)
public class OmanDemoStaffUserSeeder implements CommandLineRunner {

    static final String DEFAULT_PASSWORD = "OmanDemo@123";
    static final String EMAIL_DOMAIN = "@oman-demo.bipros.demo";

    /**
     * First EMP code in each role bucket; the base is shifted past the pre-seeded
     * platform accounts (admin / e2e_* / dev supervisors at EMP-001..EMP-008) so
     * Oman-Demo allocation never reuses those users' rows.
     */
    private static final Map<String, Integer> ROLE_BASE = Map.of(
            "PM", 200,
            "CM", 201,
            "ENGINEER", 210,
            "SUPERVISOR", 300
    );

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final OmanDemoWorkbookReader reader;
    private final OmanDemoStaffDirectory directory;

    @Override
    public void run(String... args) {
        List<StaffMasterRow> staff = reader.readStaffMaster();
        if (staff.isEmpty()) {
            log.warn("[oman-demo staff] no staff rows resolved from workbooks; "
                    + "downstream seeders will run without a staff directory");
            return;
        }

        Map<String, Role> rolesByName = new HashMap<>();
        for (String r : List.of("SUPERVISOR", "ENGINEER", "QUALITY_ENGINEER",
                "CM_MANAGER", "PROJECT_MANAGER")) {
            roleRepository.findByName(r).ifPresent(role -> rolesByName.put(r, role));
        }
        if (!rolesByName.containsKey("SUPERVISOR")) {
            log.warn("[oman-demo staff] SUPERVISOR role missing — RBAC bootstrap may not have run; "
                    + "skipping user creation");
            return;
        }

        String hashedDefault = passwordEncoder.encode(DEFAULT_PASSWORD);
        Map<String, Integer> nextOffset = new HashMap<>();
        int created = 0;
        int reused = 0;

        for (StaffMasterRow row : staff) {
            String cat = row.roleCategory() == null ? "" : row.roleCategory().toUpperCase();
            Integer base = ROLE_BASE.get(cat);
            if (base == null) {
                log.warn("[oman-demo staff] unknown role category '{}' for {}; skipping",
                        row.roleCategory(), row.fullName());
                continue;
            }
            int offset = nextOffset.getOrDefault(cat, 0);
            String empCode = String.format("EMP-%03d", base + offset);
            nextOffset.put(cat, offset + 1);

            // Lookup precedence: employee_code → username (same value)
            Optional<User> existing = userRepository.findByEmployeeCode(empCode);
            if (existing.isEmpty()) existing = userRepository.findByUsername(empCode);
            // Guard: only reuse if the matched user is actually an Oman-Demo user.
            // Without this check a shared EMP-XXX could pull in an unrelated account
            // (e.g. e2e_smanager) and we'd silently attach OMAN-Demo-Khasab roles to it.
            if (existing.isPresent() && !isOmanDemoUser(existing.get())) {
                log.warn("[oman-demo staff] {} is occupied by non-Oman-Demo user '{}' "
                                + "(email={}); skipping {} to avoid cross-tenant role leak",
                        empCode, existing.get().getUsername(), existing.get().getEmail(),
                        row.fullName());
                continue;
            }
            String email = empCode.toLowerCase() + EMAIL_DOMAIN;

            User user;
            if (existing.isPresent()) {
                user = existing.get();
                reused++;
                // Keep employee_code in sync even if a prior run set username only.
                if (user.getEmployeeCode() == null) {
                    user.setEmployeeCode(empCode);
                    try {
                        user = userRepository.save(user);
                    } catch (Exception ignored) { /* leave as-is */ }
                }
            } else if (userRepository.existsByEmail(email)) {
                log.warn("[oman-demo staff] email collision for '{}' on {} — skipping",
                        row.fullName(), email);
                continue;
            } else {
                user = new User(empCode, email, hashedDefault);
                String[] parts = splitName(row.fullName());
                user.setFirstName(parts[0]);
                user.setLastName(parts[1]);
                user.setEmployeeCode(empCode);
                user.setDesignation(truncate(row.designation(), 120));
                user.setPrimaryIcpmsRole(row.roleCategory() + " — OMAN-Demo-Khasab");
                user.setDepartment(safeDepartment(row.department()));
                user.setAuthMethods(EnumSet.of(AuthMethod.USERNAME_PASSWORD));
                user.setEnabled(true);
                try {
                    user = userRepository.save(user);
                    created++;
                } catch (Exception e) {
                    log.warn("[oman-demo staff] failed to create '{}' ({}): {}",
                            row.fullName(), empCode, e.getMessage());
                    continue;
                }
            }

            attachRolesIfMissing(user, row, rolesByName);
            directory.register(row.fullName(), user.getId(), row.roleCategory());
        }

        log.info("[oman-demo staff] {} users registered ({} created, {} reused; "
                        + "supervisors={}, engineers={}, CMs={}, PMs={})",
                directory.size(), created, reused,
                directory.supervisorIds().size(), directory.engineerIds().size(),
                directory.cmIds().size(), directory.pmIds().size());
    }

    private void attachRolesIfMissing(User user, StaffMasterRow row, Map<String, Role> rolesByName) {
        switch (row.roleCategory()) {
            case "SUPERVISOR" -> attachRole(user, rolesByName.get("SUPERVISOR"));
            case "ENGINEER" -> {
                attachRole(user, rolesByName.get("ENGINEER"));
                String desig = row.designation() == null ? "" : row.designation().toUpperCase();
                if (desig.contains("QA") || desig.contains("QC") || desig.contains("QUALITY")) {
                    attachRole(user, rolesByName.get("QUALITY_ENGINEER"));
                }
            }
            case "CM" -> attachRole(user, rolesByName.get("CM_MANAGER"));
            case "PM" -> attachRole(user, rolesByName.get("PROJECT_MANAGER"));
            default -> { /* no role */ }
        }
    }

    private void attachRole(User user, Role role) {
        if (role == null) return;
        if (userRoleRepository.existsByUserIdAndRoleId(user.getId(), role.getId())) return;
        try {
            userRoleRepository.save(new UserRole(user.getId(), role.getId()));
        } catch (Exception e) {
            log.warn("[oman-demo staff] role attach failed for {}: {}",
                    user.getUsername(), e.getMessage());
        }
    }

    /** True when a User belongs to the OMAN-Demo-Khasab tenant. */
    private static boolean isOmanDemoUser(User u) {
        String email = u.getEmail();
        if (email != null && email.endsWith(EMAIL_DOMAIN)) return true;
        String role = u.getPrimaryIcpmsRole();
        return role != null && role.contains("OMAN-Demo-Khasab");
    }

    static String[] splitName(String fullName) {
        String trimmed = fullName.trim();
        int sp = trimmed.lastIndexOf(' ');
        if (sp <= 0) return new String[]{trimmed, ""};
        return new String[]{trimmed.substring(0, sp).trim(), trimmed.substring(sp + 1).trim()};
    }

    private static Department safeDepartment(String raw) {
        if (raw == null) return Department.CIVIL;
        try {
            return Department.fromString(raw);
        } catch (Exception e) {
            return Department.CIVIL;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
