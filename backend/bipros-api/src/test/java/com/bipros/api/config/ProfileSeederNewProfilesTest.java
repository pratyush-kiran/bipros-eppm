package com.bipros.api.config;

import com.bipros.security.domain.model.Profile;
import com.bipros.security.domain.repository.ProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class ProfileSeederNewProfilesTest {

    @Autowired ProfileRepository profileRepository;

    @Test
    void seedsSiteManagerProfile() {
        Profile p = profileRepository.findByCode("SITE_MANAGER").orElseThrow();
        assertEquals("SITE_MANAGER", p.getLegacyRoleName());
        assertTrue(p.getPermissions().containsAll(Set.of(
                "PROJECT.READ", "ACTIVITY.READ", "ACTIVITY.UPDATE",
                "RESOURCE.UPDATE", "AI.READ")));
    }

    @Test
    void seedsProjectEngineerProfile() {
        Profile p = profileRepository.findByCode("PROJECT_ENGINEER").orElseThrow();
        assertEquals("PROJECT_ENGINEER", p.getLegacyRoleName());
        assertTrue(p.getPermissions().contains("YIELD_VARIANCE.READ"));
    }

    @Test
    void seedsQcManagerProfile() {
        Profile p = profileRepository.findByCode("QA_QC_ENGINEER").orElseThrow();
        assertEquals("QA_QC_ENGINEER", p.getLegacyRoleName());
        assertTrue(p.getPermissions().containsAll(Set.of(
                "NCR.CREATE", "NCR.READ", "NCR.UPDATE", "DPR.QC_ANNOTATE")));
    }

    @Test
    void seedsBimDataCoordinatorProfile() {
        Profile p = profileRepository.findByCode("BIM_DATA_COORDINATOR").orElseThrow();
        assertEquals("BIM_DATA_COORDINATOR", p.getLegacyRoleName());
        assertTrue(p.getPermissions().containsAll(Set.of(
                "DATA_QUALITY.READ", "DATA_QUALITY.AUDIT")));
    }

    @Test
    void projectManagerHasAiWrite() {
        Profile p = profileRepository.findByCode("PROJECT_MANAGER").orElseThrow();
        assertTrue(p.getPermissions().contains("AI.WRITE"),
                "PROJECT_MANAGER must now include AI.WRITE");
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.bipros.api.config.ProfileSeeder seeder;

    @Test
    void selfHealsSystemDefaultProfileWithNewPermission() {
        // SITE_MANAGER was just seeded — strip a permission, re-run seeder, expect it back.
        com.bipros.security.domain.model.Profile p =
                profileRepository.findByCode("SITE_MANAGER").orElseThrow();
        assertTrue(p.isSystemDefault());
        p.getPermissions().remove("AI.READ");
        profileRepository.save(p);

        com.bipros.security.domain.model.Profile after1 =
                profileRepository.findByCode("SITE_MANAGER").orElseThrow();
        assertTrue(!after1.getPermissions().contains("AI.READ"));

        seeder.seed();

        com.bipros.security.domain.model.Profile after2 =
                profileRepository.findByCode("SITE_MANAGER").orElseThrow();
        assertTrue(after2.getPermissions().contains("AI.READ"),
                "Self-heal must restore missing permission on system-default profile");
    }

    @Test
    void seedsPlanningEngineerProfile() {
        Profile p = profileRepository.findByCode("PLANNING_ENGINEER").orElseThrow();
        assertEquals("PLANNING_ENGINEER", p.getLegacyRoleName());
        assertTrue(p.isSystemDefault());
        assertTrue(p.getPermissions().containsAll(Set.of(
                "ACTIVITY.CREATE", "SCHEDULE.UPDATE", "BASELINE.CREATE")));
    }

    @Test
    void seedsSupervisorProfile() {
        Profile p = profileRepository.findByCode("SUPERVISOR").orElseThrow();
        assertEquals("SUPERVISOR", p.getLegacyRoleName());
        assertTrue(p.isSystemDefault());
        assertTrue(p.getPermissions().containsAll(Set.of(
                "DPR.CREATE", "DPR.UPDATE", "SAFETY.INCIDENT_LOG")));
    }

    @Test
    void seedsForemanProfile() {
        Profile p = profileRepository.findByCode("FOREMAN").orElseThrow();
        assertEquals("FOREMAN", p.getLegacyRoleName());
        assertTrue(p.isSystemDefault());
        assertTrue(p.getPermissions().containsAll(Set.of(
                "DPR.CREATE", "ACTIVITY.READ", "SAFETY.INCIDENT_LOG")));
        assertFalse(p.getPermissions().contains("PROJECT.DELETE"),
                "FOREMAN must not have PROJECT.DELETE");
    }

    @Test
    void seedsStoreManagerProfile() {
        Profile p = profileRepository.findByCode("STORE_MANAGER").orElseThrow();
        assertEquals("STORE_MANAGER", p.getLegacyRoleName());
        assertTrue(p.isSystemDefault());
        assertTrue(p.getPermissions().containsAll(Set.of(
                "RESOURCE.UPDATE", "DOCUMENT.CREATE", "REPORT.READ")));
    }

    @Test
    void seedsProcurementOfficerProfile() {
        Profile p = profileRepository.findByCode("PROCUREMENT_OFFICER").orElseThrow();
        assertEquals("PROCUREMENT_OFFICER", p.getLegacyRoleName());
        assertTrue(p.isSystemDefault());
        assertTrue(p.getPermissions().containsAll(Set.of(
                "CONTRACT.READ", "CONTRACT.UPDATE", "RESOURCE.UPDATE")));
    }

    @Test
    void seedsSafetyOfficerProfile() {
        Profile p = profileRepository.findByCode("SAFETY_OFFICER").orElseThrow();
        assertEquals("SAFETY_OFFICER", p.getLegacyRoleName());
        assertTrue(p.isSystemDefault());
        assertTrue(p.getPermissions().containsAll(Set.of(
                "SAFETY.READ", "SAFETY.INCIDENT_LOG", "PERMIT.APPROVE")));
    }

    @Test
    void seedsContractorProfile() {
        Profile p = profileRepository.findByCode("CONTRACTOR").orElseThrow();
        assertEquals("CONTRACTOR", p.getLegacyRoleName());
        assertTrue(p.isSystemDefault());
        assertTrue(p.getPermissions().containsAll(Set.of(
                "PROJECT.READ", "DPR.READ", "DOCUMENT.CREATE")));
        assertFalse(p.getPermissions().contains("PROJECT.DELETE"),
                "CONTRACTOR must not have PROJECT.DELETE");
    }

    @Test
    void seedsClientProfile() {
        Profile p = profileRepository.findByCode("CLIENT").orElseThrow();
        assertEquals("CLIENT", p.getLegacyRoleName());
        assertTrue(p.isSystemDefault());
        assertTrue(p.getPermissions().containsAll(Set.of(
                "PROJECT.READ", "REPORT.READ", "REPORT.EXPORT")));
        assertFalse(p.getPermissions().contains("PROJECT.UPDATE"),
                "CLIENT must not have PROJECT.UPDATE");
    }

    @Test
    void seederNowProvidesExpectedSystemDefaultProfiles() {
        long systemDefaults = profileRepository.findAll().stream()
                .filter(Profile::isSystemDefault).count();
        assertTrue(systemDefaults >= com.bipros.api.config.ProfileSeeder.defaultCount(),
                "Expected at least " + com.bipros.api.config.ProfileSeeder.defaultCount()
                        + " system-default profiles, found " + systemDefaults);
    }
}
