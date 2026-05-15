package com.bipros.api.config;

import com.bipros.security.domain.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class DataSeederNewRolesTest {

    @Autowired RoleRepository roleRepository;

    @Test
    void seedsAllFourNewRoles() {
        for (String name : new String[]{
                "SITE_MANAGER", "PROJECT_ENGINEER", "QA_QC_ENGINEER", "BIM_DATA_COORDINATOR"
        }) {
            assertTrue(roleRepository.findByName(name).isPresent(),
                    "Missing role after seed: " + name);
        }
    }
}
