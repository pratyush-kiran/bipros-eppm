package com.bipros.api.config.seeder;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Shared, in-process directory of OMAN-Demo staff. Populated by
 * {@link OmanDemoStaffUserSeeder} and consumed by every downstream OMAN-Demo seeder
 * (WBS+activity assignment, DPR supervisor resolution, historical performance, etc.).
 *
 * <p>Keyed by case-insensitive display name. Supports substring fuzzy matching so the
 * workbook spellings ({@code "A.K.mishra"} vs the seeded {@code "A.K. Mishra"}) still
 * resolve. Carries one round-robin pointer per role so the WBS seeder can fall back
 * to deterministic assignment when the workbook doesn't have a real-data anchor for
 * an activity.
 */
@Component
public class OmanDemoStaffDirectory {

    private final Map<String, UUID> userIdByLowerName = new HashMap<>();
    private final List<UUID> supervisorIds = new ArrayList<>();
    private final List<UUID> engineerIds = new ArrayList<>();
    private final List<UUID> cmIds = new ArrayList<>();
    private final List<UUID> pmIds = new ArrayList<>();

    private int supervisorRoundRobin = 0;
    private int engineerRoundRobin = 0;
    private int cmRoundRobin = 0;

    /** Register a user with their canonical role category. */
    public void register(String displayName, UUID userId, String roleCategory) {
        if (displayName == null || userId == null) return;
        userIdByLowerName.put(displayName.toLowerCase(Locale.ROOT).trim(), userId);
        switch (roleCategory == null ? "" : roleCategory.toUpperCase(Locale.ROOT)) {
            case "SUPERVISOR" -> supervisorIds.add(userId);
            case "ENGINEER" -> engineerIds.add(userId);
            case "CM" -> cmIds.add(userId);
            case "PM" -> pmIds.add(userId);
            default -> { /* ignore */ }
        }
    }

    /**
     * Resolve a workbook display name to a user id. Tries exact (case-insensitive)
     * first, then a contains-match in either direction for robustness against minor
     * punctuation drift (e.g. {@code "A.K.mishra"} vs {@code "A.K. Mishra"}).
     */
    public UUID resolve(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.toLowerCase(Locale.ROOT).trim();
        UUID exact = userIdByLowerName.get(key);
        if (exact != null) return exact;
        // Compact form: strip dots and spaces so "A K Singh" / "A.K. Singh" / "AKSingh" collide.
        String compact = key.replaceAll("[\\s.]+", "");
        for (Map.Entry<String, UUID> e : userIdByLowerName.entrySet()) {
            String otherCompact = e.getKey().replaceAll("[\\s.]+", "");
            if (otherCompact.equals(compact)) return e.getValue();
            if (e.getKey().contains(key) || key.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    public UUID nextSupervisor() {
        if (supervisorIds.isEmpty()) return null;
        UUID u = supervisorIds.get(supervisorRoundRobin % supervisorIds.size());
        supervisorRoundRobin++;
        return u;
    }

    public UUID nextEngineer() {
        if (engineerIds.isEmpty()) return null;
        UUID u = engineerIds.get(engineerRoundRobin % engineerIds.size());
        engineerRoundRobin++;
        return u;
    }

    public UUID nextCm() {
        if (cmIds.isEmpty()) return null;
        UUID u = cmIds.get(cmRoundRobin % cmIds.size());
        cmRoundRobin++;
        return u;
    }

    public UUID anyPm() {
        return pmIds.isEmpty() ? null : pmIds.get(0);
    }

    public List<UUID> supervisorIds() { return List.copyOf(supervisorIds); }
    public List<UUID> engineerIds() { return List.copyOf(engineerIds); }
    public List<UUID> cmIds() { return List.copyOf(cmIds); }
    public List<UUID> pmIds() { return List.copyOf(pmIds); }

    public boolean isPopulated() {
        return !userIdByLowerName.isEmpty();
    }

    public int size() {
        return userIdByLowerName.size();
    }
}
