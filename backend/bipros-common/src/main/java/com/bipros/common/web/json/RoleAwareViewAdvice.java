package com.bipros.common.web.json;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Promotes the active Jackson {@code @JsonView} on every JSON response based on the current
 * user's roles. Without this advice, controllers would have to pick a view manually.
 *
 * <p>Behaviour:
 * <ol>
 *   <li>Determine the widest {@link Views} class the caller's roles entitle them to (see role
 *       table in {@link Views}). This is the <b>upper bound</b> on what may be serialised.</li>
 *   <li>If the controller method (or class) declares an explicit {@code @JsonView}, intersect
 *       it with the role-derived view: <b>pick the narrower of the two</b>. An explicit view
 *       may only ever <i>narrow</i> the response — it can never widen past what the caller's
 *       roles allow. Without this rule, a controller author who annotates a method with
 *       {@code @JsonView(Views.Admin.class)} but forgets the matching {@code @PreAuthorize}
 *       would leak admin-only fields to every authenticated user.</li>
 *   <li>Wrap the response body in a {@link MappingJacksonValue} with the resolved view set.</li>
 * </ol>
 *
 * <p>Note: {@code @JsonView} only filters response serialisation. Write endpoints must still
 * validate that the caller is allowed to set sensitive fields — the advice cannot enforce that.
 */
// Scoped to our own controllers. springdoc's OpenAPI endpoints (/v3/api-docs, /v3/api-docs.yaml)
// live in org.springdoc.* and return raw byte[] with content-type application/json. If this
// advice applies to them, byte[] gets wrapped in a MappingJacksonValue and the converter chain
// throws ClassCastException at write time. Restricting basePackages to com.bipros side-steps
// the entire problem without leaning on fragile converter-type sniffing.
@RestControllerAdvice(basePackages = "com.bipros")
@RequiredArgsConstructor
public class RoleAwareViewAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // Belt-and-braces: also gate by converter type so non-Jackson responses (file streams,
        // raw byte[] downloads) inside our own controllers bypass JsonView wrapping.
        return AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body == null) {
            return null;
        }
        // Don't wrap non-JSON responses (file streams, plain strings, etc.).
        if (selectedContentType != null && !selectedContentType.includes(MediaType.APPLICATION_JSON)) {
            return body;
        }

        Class<?> roleView = pickViewForCurrentUser();

        // Pick the narrower of the role-derived view and any explicit @JsonView. Explicit
        // annotations may only restrict, never widen.
        JsonView explicit = returnType.getMethodAnnotation(JsonView.class);
        if (explicit == null && returnType.getDeclaringClass() != null) {
            explicit = returnType.getDeclaringClass().getAnnotation(JsonView.class);
        }
        Class<?> resolvedView = (explicit != null && explicit.value().length > 0)
                ? narrower(roleView, explicit.value()[0])
                : roleView;

        if (body instanceof MappingJacksonValue existing) {
            // Even if the controller pre-set a view via MappingJacksonValue, intersect it with
            // the role-derived ceiling. This closes the loophole where a service method returns
            // a hand-built MappingJacksonValue carrying a wider-than-allowed view.
            Class<?> existingView = existing.getSerializationView();
            existing.setSerializationView(existingView == null ? resolvedView : narrower(roleView, existingView));
            return existing;
        }
        MappingJacksonValue wrapped = new MappingJacksonValue(body);
        wrapped.setSerializationView(resolvedView);
        return wrapped;
    }

    /**
     * Returns the narrower of two view classes, where "narrower" = serialises fewer fields.
     *
     * <p>Within our hierarchy {@code Public ⊂ Internal ⊂ FinanceConfidential ⊂ Admin}, a view
     * "extends" its parent, and a Jackson view filter includes a field whose tag is the active
     * view OR any ancestor of it. So the WIDER view is the one further down the chain.
     *
     * <p>Java's {@code A.isAssignableFrom(B)} returns true when {@code B} extends/implements
     * {@code A} — i.e. {@code A} is the parent. In our hierarchy that means {@code A} is the
     * narrower view.
     *
     * <p>Edge cases:
     * <ul>
     *   <li>If the two views are identical, return either.</li>
     *   <li>If neither is an ancestor of the other (unrelated marker classes from outside the
     *       {@link Views} hierarchy), default to the role-derived view as the safe ceiling.</li>
     * </ul>
     */
    static Class<?> narrower(Class<?> roleView, Class<?> explicit) {
        if (roleView == explicit) {
            return roleView;
        }
        // explicit IS-A roleView ⇒ explicit is wider (a sub-view) ⇒ pick roleView as narrower
        if (roleView.isAssignableFrom(explicit)) {
            return roleView;
        }
        // roleView IS-A explicit ⇒ roleView is wider ⇒ pick explicit as narrower
        if (explicit.isAssignableFrom(roleView)) {
            return explicit;
        }
        // Unrelated views — fall back to roleView (the ceiling we're sure the caller may see).
        return roleView;
    }

    private Class<?> pickViewForCurrentUser() {
        Set<String> roles = currentRoles();
        if (roles.contains("ROLE_ADMIN")) {
            return Views.Admin.class;
        }
        if (roles.contains("ROLE_FINANCE")) {
            return Views.FinanceConfidential.class;
        }
        if (roles.contains("ROLE_PMO")
                || roles.contains("ROLE_EXECUTIVE")
                || roles.contains("ROLE_PROJECT_MANAGER")) {
            return Views.Internal.class;
        }
        return Views.Public.class;
    }

    private static Set<String> currentRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
