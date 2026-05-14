package com.bipros.api.config.seeder.util;

import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.bipros.resource.domain.repository.ResourceTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shared lookup-or-create helpers used by demo-data seeders to attach a non-null
 * {@link ResourceType} and {@link ResourceRole} to every new {@code Resource}.
 *
 * <p>Both {@code ResourceType} and {@code ResourceRole} are required (non-nullable) on the slim
 * post-rewrite {@code Resource}. These helpers cache lookups across a single seed run so we don't
 * hit the DB once per row.
 */
@Component
@RequiredArgsConstructor
public class SeederResourceFactory {

    private final ResourceTypeRepository resourceTypeRepository;
    private final ResourceRoleRepository resourceRoleRepository;

    private final Map<String, ResourceType> typeCache = new HashMap<>();
    private final Map<String, ResourceRole> roleCache = new HashMap<>();

    /**
     * Lookup the system-default {@link ResourceType} by code (LABOR / EQUIPMENT / MATERIAL).
     * Falls back to LABOR if an unknown code is passed.
     */
    @Transactional
    public ResourceType requireType(String code) {
        String normalised = normaliseTypeCode(code);
        ResourceType cached = typeCache.get(normalised);
        if (cached != null) {
            return cached;
        }
        ResourceType type = resourceTypeRepository.findByCode(normalised)
            .orElseThrow(() -> new IllegalStateException(
                "Required ResourceType " + normalised + " has not been seeded — "
                    + "ResourceTypeSeeder must run first"));
        typeCache.put(normalised, type);
        return type;
    }

    /**
     * Look up an existing {@link ResourceRole} by code, or create a generic "imported" role
     * tied to the given type. Mirrors the import-export module's
     * {@code ensureDefaultRoleForImport} helper.
     */
    @Transactional
    public ResourceRole ensureRole(String code, String typeCode) {
        String key = code + "|" + normaliseTypeCode(typeCode);
        ResourceRole cached = roleCache.get(key);
        if (cached != null) {
            return cached;
        }
        ResourceRole role = resourceRoleRepository.findByCode(code)
            .orElseGet(() -> {
                ResourceType type = requireType(typeCode);
                ResourceRole newRole = new ResourceRole();
                newRole.setCode(code);
                newRole.setName(toTitleCase(code));
                newRole.setResourceType(type);
                newRole.setActive(true);
                newRole.setSortOrder(999);
                return resourceRoleRepository.save(newRole);
            });
        roleCache.put(key, role);
        return role;
    }

    /**
     * Lookup-or-create the catch-all "IMPORTED-{TYPE}" role used when a seeder doesn't have a
     * concrete role intent for the row.
     */
    @Transactional
    public ResourceRole ensureDefaultRole(String typeCode) {
        String normalised = normaliseTypeCode(typeCode);
        return ensureRole("IMPORTED-" + normalised, normalised);
    }

    private static String normaliseTypeCode(String code) {
        if (code == null) {
            return "MANPOWER";
        }
        String upper = code.toUpperCase(Locale.ROOT).trim();
        return switch (upper) {
            case "LABOR", "LABOUR", "L", "MANPOWER" -> "MANPOWER";
            case "MATERIAL", "M" -> "MATERIAL";
            case "EQUIPMENT", "E", "NONLABOR", "MACHINE" -> "EQUIPMENT";
            default -> "MANPOWER";
        };
    }

    private static String toTitleCase(String code) {
        if (code == null || code.isBlank()) return code;
        String[] parts = code.replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT).split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        return sb.toString();
    }
}
