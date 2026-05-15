package com.bipros.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression gate: every controller endpoint registered in the Spring MVC handler mapping must be
 * either guarded by {@link PreAuthorize}/{@link PostAuthorize} (on the method or its containing
 * class) or explicitly enumerated in the permit-all path list configured by
 * {@code SecurityConfig}. Future PRs that ship an unguarded controller will fail this test with
 * a printable list of the offending endpoints.
 *
 * <p>Loads the Spring context with {@code webEnvironment = NONE} — we only need the
 * {@link RequestMappingHandlerMapping} bean, no embedded server is required. Profile {@code test}
 * is the project convention (matches the rest of {@code bipros-api}'s integration tests) and
 * activates the Testcontainers-backed Postgres so the full context can wire up.
 *
 * <p>The {@link #PUBLIC_PATTERNS} set mirrors the {@code permitAll()} matchers in
 * {@code com.bipros.security.infrastructure.config.SecurityConfig}. Keep them in sync: when
 * SecurityConfig adds or removes a public matcher, update this set in the same PR.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("RBAC coverage — every controller method is guarded or explicitly public")
class RbacCoverageIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("bipros_test")
            .withUsername("postgres")
            .withPassword("postgres");

    /**
     * Ant-style patterns considered explicitly public — every entry here MUST be backed by a
     * {@code .permitAll()} rule in {@code SecurityConfig}. The Set is intentionally tiny: any
     * other endpoint that needs to be publicly reachable should be added here AND in
     * SecurityConfig in the same change.
     */
    private static final Set<String> PUBLIC_PATTERNS;

    static {
        Set<String> p = new LinkedHashSet<>();
        // Authentication endpoints (POST-only in SecurityConfig; pattern set is method-agnostic
        // here because the handler scan checks the URL pattern, and a GET/PUT auth endpoint that
        // shipped without @PreAuthorize would still be a security bug we want surfaced).
        p.add("/v1/auth/login");
        p.add("/v1/auth/register");
        p.add("/v1/auth/refresh");
        // Permit verification (public QR-code style verifier).
        p.add("/v1/permits/verify/**");
        // Actuator — only health and info are public per SecurityConfig; other actuator endpoints
        // are role-gated and must therefore carry @PreAuthorize (or be acceptable as anonymous
        // actuator probes — which they aren't in this codebase).
        p.add("/actuator/health");
        p.add("/actuator/health/**");
        p.add("/actuator/info");
        // OpenAPI / Swagger UI.
        p.add("/v3/api-docs");
        p.add("/v3/api-docs/**");
        p.add("/swagger-ui");
        p.add("/swagger-ui/**");
        p.add("/swagger-ui.html");
        p.add("/swagger-resources/**");
        p.add("/webjars/**");
        PUBLIC_PATTERNS = Collections.unmodifiableSet(p);
    }

    /**
     * Framework / library packages whose handlers we intentionally skip — they are not part of
     * the application's controller surface and the framework owns their security posture.
     */
    private static final List<String> SKIPPED_HANDLER_PACKAGE_PREFIXES = List.of(
            "org.springframework.",
            "org.springdoc.",
            "springfox."
    );

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("Every controller handler method has @PreAuthorize/@PostAuthorize or is in the permit-all list")
    void rbacCoverage_allHandlerMethodsAreGuardedOrExplicitlyPublic() {
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();
        assertThat(handlerMethods)
                .as("Expected RequestMappingHandlerMapping to expose registered controller methods")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            RequestMappingInfo mappingInfo = entry.getKey();
            HandlerMethod handlerMethod = entry.getValue();
            Class<?> handlerClass = handlerMethod.getBeanType();

            if (isSkippedHandler(handlerClass)) {
                continue;
            }

            Set<String> patterns = extractPatterns(mappingInfo);
            if (patterns.isEmpty()) {
                // No URL pattern (e.g. a handler bound by other conditions only). Treat as
                // requiring an annotation, since we can't reason about public-path matching.
                if (!isAnnotated(handlerMethod)) {
                    violations.add(formatViolation(handlerMethod, Set.of("<no-url-pattern>")));
                }
                continue;
            }

            boolean methodAnnotated = isAnnotated(handlerMethod);
            if (methodAnnotated) {
                continue;
            }

            // No annotation — the ONLY way to pass is if every URL pattern this method maps to
            // is covered by the permit-all list. If even one pattern is non-public, the method
            // is treated as unguarded (because attacking that one path would be enough).
            boolean allPatternsPublic = patterns.stream().allMatch(this::matchesAnyPublicPattern);
            if (!allPatternsPublic) {
                violations.add(formatViolation(handlerMethod, patterns));
            }
        }

        Collections.sort(violations);

        assertThat(violations)
                .as("Controllers must declare @PreAuthorize/@PostAuthorize (method or class) or "
                        + "be enumerated in the permit-all path list. Unguarded handlers:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //   Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static boolean isSkippedHandler(Class<?> handlerClass) {
        String name = handlerClass.getName();
        for (String prefix : SKIPPED_HANDLER_PACKAGE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnnotated(HandlerMethod handlerMethod) {
        Method method = handlerMethod.getMethod();
        if (method.isAnnotationPresent(PreAuthorize.class)
                || method.isAnnotationPresent(PostAuthorize.class)) {
            return true;
        }
        Class<?> declaringClass = method.getDeclaringClass();
        if (declaringClass.isAnnotationPresent(PreAuthorize.class)
                || declaringClass.isAnnotationPresent(PostAuthorize.class)) {
            return true;
        }
        // Also check the bean type — in some proxying scenarios the declared method lives on an
        // interface while the @PreAuthorize sits on the concrete bean class (or vice versa).
        Class<?> beanType = handlerMethod.getBeanType();
        if (beanType != declaringClass
                && (beanType.isAnnotationPresent(PreAuthorize.class)
                        || beanType.isAnnotationPresent(PostAuthorize.class))) {
            return true;
        }
        return false;
    }

    /**
     * Pull URL patterns out of a {@link RequestMappingInfo} in a Spring-6-compatible way: prefer
     * the PathPattern-based condition (the new default), fall back to the legacy
     * {@link PatternsRequestCondition} if path patterns are not configured.
     */
    private static Set<String> extractPatterns(RequestMappingInfo info) {
        Set<String> patterns = new LinkedHashSet<>();
        PathPatternsRequestCondition pathPatterns = info.getPathPatternsCondition();
        if (pathPatterns != null && !pathPatterns.isEmptyPathMapping()) {
            pathPatterns.getPatternValues().forEach(p -> {
                if (p != null && !p.isBlank()) {
                    patterns.add(p);
                }
            });
        }
        if (patterns.isEmpty()) {
            PatternsRequestCondition legacy = info.getPatternsCondition();
            if (legacy != null) {
                legacy.getPatterns().forEach(p -> {
                    if (p != null && !p.isBlank()) {
                        patterns.add(p);
                    }
                });
            }
        }
        return patterns;
    }

    private boolean matchesAnyPublicPattern(String url) {
        for (String publicPattern : PUBLIC_PATTERNS) {
            // Exact / literal match first for speed and to handle patterns that contain no
            // wildcards.
            if (publicPattern.equals(url)) {
                return true;
            }
            // Ant-style match handles "/v1/permits/verify/**" vs the concrete handler pattern
            // "/v1/permits/verify/{token}" — AntPathMatcher treats the "**" as covering one or
            // more path segments. We match in BOTH directions to be safe: a wildcard handler
            // pattern (e.g. a controller declared at "/v3/api-docs/**") should still be marked
            // public even though the public-list entry is a more specific literal.
            if (PATH_MATCHER.match(publicPattern, url)) {
                return true;
            }
            if (PATH_MATCHER.match(url, publicPattern)) {
                return true;
            }
        }
        return false;
    }

    private static String formatViolation(HandlerMethod handlerMethod, Set<String> patterns) {
        String controller = handlerMethod.getBeanType().getSimpleName();
        String method = handlerMethod.getMethod().getName();
        String patternList = String.join(", ", new ArrayList<>(patterns));
        return controller + "." + method + " [" + patternList + "]";
    }

    @SuppressWarnings("unused") // retained for IDE discoverability / future debugging
    private static List<String> describeAllHandlers(Map<RequestMappingInfo, HandlerMethod> handlers) {
        return handlers.entrySet().stream()
                .map(e -> e.getValue().getBeanType().getSimpleName()
                        + "." + e.getValue().getMethod().getName()
                        + " " + Arrays.toString(extractPatterns(e.getKey()).toArray()))
                .sorted()
                .toList();
    }
}
