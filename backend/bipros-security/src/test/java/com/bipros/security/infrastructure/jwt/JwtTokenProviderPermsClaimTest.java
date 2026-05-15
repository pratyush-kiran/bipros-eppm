package com.bipros.security.infrastructure.jwt;

import com.bipros.security.application.service.CurrentUserService;
import com.bipros.security.domain.model.PermissionCatalog;
import com.bipros.security.domain.model.Profile;
import com.bipros.security.domain.model.Role;
import com.bipros.security.domain.model.RolePermissionMatrix;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.model.UserRole;
import com.bipros.security.domain.repository.ProfileRepository;
import com.bipros.security.domain.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.SecretKey;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link JwtTokenProvider#generateAccessToken(User)} embeds the user's effective
 * permission set as a sorted, comma-joined {@code perms} claim and that the refresh-token path
 * remains permission-free.
 *
 * <p>Wires a real {@link CurrentUserService} on top of mocked {@link UserRepository} /
 * {@link ProfileRepository} so the token-issue path exercises the same code that {@code /me}
 * uses to compute permissions for a user reference (Phase 2.A).
 */
@ExtendWith(MockitoExtension.class)
class JwtTokenProviderPermsClaimTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256";

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProfileRepository profileRepository;

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(TEST_SECRET);
        props.setAccessTokenExpiration(3_600_000L);
        props.setRefreshTokenExpiration(86_400_000L);

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");

        CurrentUserService currentUserService = new CurrentUserService(userRepository, profileRepository);
        jwtTokenProvider = new JwtTokenProvider(props, env, currentUserService);
        jwtTokenProvider.validateSecret();
    }

    // ── Cases ────────────────────────────────────────────────────────────────

    @Test
    void userWithSiteEngineerRole_tokenHasDprAndActivityPerms() {
        User user = fixture("eve", null, "SITE_ENGINEER");

        String token = jwtTokenProvider.generateAccessToken(user);
        Claims claims = parse(token);

        String permsClaim = claims.get("perms", String.class);
        assertThat(permsClaim).isNotBlank();
        Set<String> perms = split(permsClaim);
        assertThat(perms).contains("DPR.CREATE", "ACTIVITY.UPDATE");
        assertThat(perms).doesNotContain("PROJECT.DELETE");
    }

    @Test
    void adminUser_permsClaimContainsAll() {
        User user = fixture("root", null, "ADMIN");

        String token = jwtTokenProvider.generateAccessToken(user);
        Claims claims = parse(token);

        Set<String> perms = split(claims.get("perms", String.class));
        assertThat(perms).hasSize(PermissionCatalog.ALL_CODES.size());
        assertThat(perms).containsExactlyInAnyOrderElementsOf(PermissionCatalog.ALL_CODES);
    }

    @Test
    void userWithProfileAndRole_permsUnionsBoth() {
        UUID profileId = UUID.randomUUID();
        User user = fixture("sam", profileId, "SUPERVISOR");
        Profile profile = profileWithPermissions("REPORT.EXPORT");
        when(profileRepository.findById(profileId)).thenReturn(Optional.of(profile));

        String token = jwtTokenProvider.generateAccessToken(user);
        Claims claims = parse(token);

        Set<String> perms = split(claims.get("perms", String.class));
        // SUPERVISOR contributes DPR.CREATE from the matrix; profile contributes REPORT.EXPORT.
        assertThat(perms).contains("DPR.CREATE", "REPORT.EXPORT");
        // Sanity: the union must contain at least everything SUPERVISOR ships with plus the extra.
        Set<String> expectedMin = new HashSet<>(RolePermissionMatrix.permissionsFor("SUPERVISOR"));
        expectedMin.add("REPORT.EXPORT");
        assertThat(perms).containsAll(expectedMin);
    }

    @Test
    void permsClaimIsSortedAndCommaJoined() {
        User user = fixture("kim", null, "SITE_ENGINEER");

        String token = jwtTokenProvider.generateAccessToken(user);
        Claims claims = parse(token);

        String permsClaim = claims.get("perms", String.class);
        List<String> parts = Arrays.asList(permsClaim.split(","));
        assertThat(parts).isNotEmpty();
        // Ascending alphabetical order (TreeSet semantics).
        assertThat(parts).isSortedAccordingTo(String::compareTo);
        // No whitespace padding.
        assertThat(parts).allSatisfy(p -> assertThat(p).doesNotContain(" "));
    }

    @Test
    void refreshTokenHasNoPermsClaim() {
        User user = fixture("kim", null, "SITE_ENGINEER");

        String token = jwtTokenProvider.generateRefreshToken(user);
        Claims claims = parse(token);

        assertThat(claims.get("perms")).isNull();
        assertThat(claims.get("roles")).isNull();
        assertThat(claims.getSubject()).isEqualTo("kim");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Claims parse(String token) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes());
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static Set<String> split(String claim) {
        if (claim == null || claim.isEmpty()) {
            return Set.of();
        }
        return new TreeSet<>(Arrays.asList(claim.split(",")));
    }

    private static User fixture(String username, UUID profileId, String... roleNames) {
        User user = new User(username, username + "@example.com", "{noop}hashed");
        user.setEnabled(true);
        user.setAccountLocked(false);
        user.setProfileId(profileId);
        Set<UserRole> roles = new HashSet<>();
        for (String name : roleNames) {
            Role role = new Role(name);
            UserRole ur = new UserRole();
            ur.setRole(role);
            roles.add(ur);
        }
        user.setRoles(roles);
        return user;
    }

    private static Profile profileWithPermissions(String... codes) {
        Set<String> perms = new HashSet<>(Arrays.asList(codes));
        return new Profile("TEST_PROFILE", "Test Profile", "fixture",
                "TESTER", false, perms);
    }
}
