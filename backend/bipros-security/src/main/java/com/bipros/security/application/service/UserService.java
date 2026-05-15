package com.bipros.security.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.security.application.dto.CreateUserRequest;
import com.bipros.security.application.dto.UpdateUserProfileRequest;
import com.bipros.security.application.dto.UpdateUserRolesRequest;
import com.bipros.security.application.dto.UserResponse;
import com.bipros.security.domain.model.Profile;
import com.bipros.security.domain.model.Role;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.model.UserRole;
import com.bipros.security.domain.repository.ProfileRepository;
import com.bipros.security.domain.repository.RoleRepository;
import com.bipros.security.domain.repository.UserRepository;
import com.bipros.security.domain.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class UserService {

    private static final Pattern EMP_CODE_PATTERN = Pattern.compile("^EMP-(\\d+)$");

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final ProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", username));

        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> listUsers(Pageable pageable) {
        return listUsers(pageable, null);
    }

    /**
     * List users, optionally filtered to those holding ANY of the given role names. Used by
     * the supervisor / staff picker on the frontend (e.g. {@code ?roles=SUPERVISOR,FOREMAN})
     * to narrow the list to operationally-relevant candidates. {@code null} or blank
     * {@code rolesCsv} falls back to the unfiltered listing.
     */
    @Transactional(readOnly = true)
    public Page<UserResponse> listUsers(Pageable pageable, String rolesCsv) {
        List<String> roleNames = parseRoleNames(rolesCsv);
        Page<User> users = roleNames.isEmpty()
                ? userRepository.findAll(pageable)
                : userRepository.findByRoleNamesAndEnabled(roleNames, pageable);
        Map<UUID, Profile> profilesById = loadProfilesFor(users.getContent());
        List<UserResponse> responses = users.getContent().stream()
                .map(u -> toResponse(u, u.getProfileId() == null ? null : profilesById.get(u.getProfileId())))
                .collect(Collectors.toList());

        return new PageImpl<>(responses, pageable, users.getTotalElements());
    }

    /** Split a {@code roles=A,B,C} query string into a clean uppercase role-name list. */
    private static List<String> parseRoleNames(String rolesCsv) {
        if (rolesCsv == null || rolesCsv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(rolesCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .distinct()
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        return toResponse(user);
    }

    /** Create a new user with hashed password and (optionally) assign a profile. */
    public UserResponse createUser(CreateUserRequest req) {
        if (userRepository.existsByUsername(req.username())) {
            throw new BusinessRuleException("DUPLICATE_USERNAME",
                    "A user with username '" + req.username() + "' already exists");
        }
        if (userRepository.existsByEmail(req.email())) {
            throw new BusinessRuleException("DUPLICATE_EMAIL",
                    "A user with email '" + req.email() + "' already exists");
        }

        User u = new User(req.username(), req.email(), passwordEncoder.encode(req.password()));
        if (req.firstName() != null) u.setFirstName(req.firstName());
        if (req.lastName() != null) u.setLastName(req.lastName());
        u.setEnabled(req.enabled() == null ? true : req.enabled());
        if (u.getEmployeeCode() == null || u.getEmployeeCode().isBlank()) {
            u.setEmployeeCode(generateEmployeeCode());
        }

        User saved = userRepository.save(u);

        if (req.profileId() != null) {
            applyProfile(saved, req.profileId());
        }

        log.info("Created user '{}' (id={}) profile={}", saved.getUsername(), saved.getId(), req.profileId());
        return toResponse(saved);
    }

    /** Toggle the user's enabled flag. */
    public UserResponse setEnabled(UUID id, boolean enabled) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && u.getUsername().equals(auth.getName()) && !enabled) {
            throw new BusinessRuleException("CANNOT_DISABLE_SELF",
                    "You cannot disable your own account");
        }
        u.setEnabled(enabled);
        userRepository.save(u);
        return toResponse(u);
    }

    /** Replace the user's role set with the provided role names. */
    public UserResponse setRoles(UUID id, UpdateUserRolesRequest req) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        applyRoles(u, req.roles() == null ? List.of() : req.roles());
        return toResponse(u);
    }

    /**
     * Assign (or clear with null) a profile and sync the user's {@code user_roles} to the
     * profile's {@code legacyRoleName}, so existing role-based @PreAuthorize guards keep working.
     */
    public UserResponse setProfile(UUID userId, UUID profileId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (profileId == null) {
            u.setProfileId(null);
            applyRoles(u, List.of());
            userRepository.save(u);
            return toResponse(u);
        }

        applyProfile(u, profileId);
        return toResponse(u);
    }

    /**
     * Partial-update a user / personnel record. Changes the Screen 07 fields (mobile, department,
     * joining/contract dates, presence status) plus name/email/designation/organisation. Auth
     * details (username, password, roles) remain the responsibility of their dedicated endpoints.
     * Auto-generates {@code employeeCode} the first time the record is saved without one.
     */
    public UserResponse updateProfile(UUID id, UpdateUserProfileRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));

        if (request.firstName() != null) user.setFirstName(request.firstName());
        if (request.lastName() != null) user.setLastName(request.lastName());
        if (request.email() != null) {
            if (!request.email().equals(user.getEmail())
                && userRepository.findByEmail(request.email()).isPresent()) {
                throw new BusinessRuleException("DUPLICATE_EMAIL",
                    "A user with email '" + request.email() + "' already exists");
            }
            user.setEmail(request.email());
        }
        if (request.mobile() != null) user.setMobile(request.mobile());
        if (request.designation() != null) user.setDesignation(request.designation());
        if (request.department() != null) user.setDepartment(request.department());
        if (request.organisationId() != null) user.setOrganisationId(request.organisationId());
        if (request.joiningDate() != null) user.setJoiningDate(request.joiningDate());
        if (request.contractEndDate() != null) user.setContractEndDate(request.contractEndDate());
        if (request.presenceStatus() != null) user.setPresenceStatus(request.presenceStatus());
        if (request.enabled() != null) user.setEnabled(request.enabled());

        if (user.getEmployeeCode() == null || user.getEmployeeCode().isBlank()) {
            user.setEmployeeCode(generateEmployeeCode());
        }

        User saved = userRepository.save(user);
        UserResponse response = toResponse(saved);
        auditService.logUpdate("User", id, "profile", null, response);
        return response;
    }

    /**
     * Ensure the given user has a non-blank {@code employeeCode}. Useful during login / bootstrap
     * so legacy rows get an EMP-NNN on first touch without requiring a manual migration.
     */
    public void ensureEmployeeCode(User user) {
        if (user.getEmployeeCode() == null || user.getEmployeeCode().isBlank()) {
            user.setEmployeeCode(generateEmployeeCode());
            userRepository.save(user);
        }
    }

    private String generateEmployeeCode() {
        int next = 1;
        for (User u : userRepository.findAll()) {
            if (u.getEmployeeCode() == null) continue;
            Matcher m = EMP_CODE_PATTERN.matcher(u.getEmployeeCode());
            if (m.matches()) {
                int n = Integer.parseInt(m.group(1));
                if (n >= next) next = n + 1;
            }
        }
        return String.format("EMP-%03d", next);
    }

    private List<String> extractRoles(User user) {
        return user.getRoles().stream()
                .map(userRole -> userRole.getRole() != null
                        ? userRole.getRole().getName()
                        : roleRepository.findById(userRole.getRoleId()).map(Role::getName).orElse(null))
                .filter(name -> name != null)
                .collect(Collectors.toList());
    }

    private UserResponse toResponse(User user) {
        Profile profile = user.getProfileId() == null
                ? null
                : profileRepository.findById(user.getProfileId()).orElse(null);
        return toResponse(user, profile);
    }

    private UserResponse toResponse(User user, Profile profile) {
        return UserResponse.from(
                user,
                extractRoles(user),
                profile != null ? profile.getId() : null,
                profile != null ? profile.getName() : null,
                List.of(),
                effectivePermissions(user)
        );
    }

    /** Effective permission union for {@code user}, sorted ascending for stable client diffs. */
    private List<String> effectivePermissions(User user) {
        return new ArrayList<>(new TreeSet<>(currentUserService.permissionsFor(user)));
    }

    private Map<UUID, Profile> loadProfilesFor(List<User> users) {
        Set<UUID> ids = users.stream()
                .map(User::getProfileId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<UUID, Profile> map = new HashMap<>();
        for (Profile p : profileRepository.findAllById(ids)) {
            map.put(p.getId(), p);
        }
        return map;
    }

    private void applyProfile(User u, UUID profileId) {
        Profile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("Profile", profileId));
        u.setProfileId(profile.getId());
        userRepository.save(u);
        applyRoles(u, List.of(profile.getLegacyRoleName()));
    }

    private void applyRoles(User u, List<String> roleNames) {
        // Resolve all target roles up-front; reject unknown names so the whole call is atomic.
        List<Role> targetRoles = new ArrayList<>();
        for (String name : roleNames) {
            Role r = roleRepository.findByName(name).orElseThrow(() ->
                    new BusinessRuleException("UNKNOWN_ROLE", "Role '" + name + "' does not exist"));
            targetRoles.add(r);
        }
        Set<UUID> targetIds = targetRoles.stream().map(Role::getId).collect(Collectors.toSet());

        // Drop existing user_roles rows that aren't in the target set.
        Set<UserRole> existing = new HashSet<>(u.getRoles());
        for (UserRole ur : existing) {
            if (!targetIds.contains(ur.getRoleId())) {
                userRoleRepository.delete(ur);
                u.getRoles().remove(ur);
            }
        }
        // Add missing rows.
        Set<UUID> haveIds = u.getRoles().stream().map(UserRole::getRoleId).collect(Collectors.toSet());
        for (Role r : targetRoles) {
            if (!haveIds.contains(r.getId())) {
                UserRole ur = new UserRole(u.getId(), r.getId());
                userRoleRepository.save(ur);
                u.getRoles().add(ur);
            }
        }
    }
}
