package com.bipros.security.infrastructure.jwt;

import com.bipros.security.application.service.CurrentUserService;
import com.bipros.security.domain.model.User;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtTokenProvider {

    private static final String DEV_DEFAULT_SECRET =
        "bipros-dev-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256";

    private final JwtProperties jwtProperties;
    private final Environment environment;
    private final CurrentUserService currentUserService;

    /**
     * Refuse to start in prod with the dev JWT default — any token signed with this key would be
     * trivially forgeable by anyone who's seen the open-source config. Forces orchestrators to
     * set {@code JWT_SECRET} before the app accepts traffic.
     */
    @PostConstruct
    void validateSecret() {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        boolean isProd = profiles.contains("prod");
        String secret = jwtProperties.getSecret();
        if (isProd && DEV_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                "Refusing to start: JWT_SECRET is unset in prod profile. "
                    + "Set the JWT_SECRET env var to a 32+ byte random string before booting.");
        }
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalStateException(
                "JWT secret must be at least 32 bytes (256 bits) for HS256");
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes());
    }

    public String generateAccessToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        List<String> roles = user.getRoles().stream()
                .map(userRole -> userRole.getRole().getName())
                .collect(Collectors.toList());
        claims.put("roles", roles);

        // Embed the user's effective permission set (role-matrix ∪ profile) as a sorted, comma-joined
        // string so a thin client can split deterministically without re-issuing a /me call.
        // SecurityContext is NOT populated at login-time JWT issuance, so we resolve permissions
        // directly from the User reference via CurrentUserService.permissionsFor(user).
        Set<String> perms = currentUserService.permissionsFor(user);
        String permsClaim = String.join(",", new TreeSet<>(perms));
        claims.put("perms", permsClaim);

        return buildToken(claims, user.getUsername(), jwtProperties.getAccessTokenExpiration());
    }

    public String generateRefreshToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        return buildToken(claims, user.getUsername(), jwtProperties.getRefreshTokenExpiration());
    }

    private String buildToken(Map<String, Object> claims, String subject, long expirationMs) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(subject)
                .claims(claims)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public String getUsernameFromToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Failed to extract username from token", e);
            throw new RuntimeException("Invalid token");
        }
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT token is expired");
        } catch (MalformedJwtException e) {
            log.warn("Invalid JWT token");
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT token validation failed");
        }
        return false;
    }

    public long getAccessTokenExpirationMs() {
        return jwtProperties.getAccessTokenExpiration();
    }
}
