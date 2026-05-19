package com.bipros.api.integration;

import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.Shift;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 — JPA-level persistence test for the new {@code shift} column on DPR child rows.
 * Saves a {@link DprEquipment} (NIGHT) and a {@link DprManpower} (NIGHT) and asserts that
 * after a flush+clear the values reload exactly. Also verifies the entity default (DAY)
 * applies when {@code shift} is left unset by the builder.
 *
 * <p>Note: this test lives in {@code bipros-api} because the Testcontainers + Liquibase
 * test infrastructure already exists here. The pure {@code bipros-project} module has no
 * test datasource wired in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("DPR child rows — shift column persistence")
class DprShiftPersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("bipros_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @Autowired private DprEquipmentRepository equipmentRepository;
    @Autowired private DprManpowerRepository manpowerRepository;

    @Test
    @DisplayName("DprEquipment with Shift.NIGHT round-trips through JPA")
    @Transactional
    void equipmentNightShiftPersists() {
        UUID dprId = UUID.randomUUID();
        DprEquipment e = DprEquipment.builder()
                .dprId(dprId)
                .equipmentType("Grader")
                .shift(Shift.NIGHT)
                .build();

        DprEquipment saved = equipmentRepository.saveAndFlush(e);
        UUID id = saved.getId();
        assertThat(id).isNotNull();

        // Force a re-read from the DB (not the persistence context cache).
        equipmentRepository.flush();
        DprEquipment reloaded = equipmentRepository.findById(id).orElseThrow();
        assertThat(reloaded.getShift()).isEqualTo(Shift.NIGHT);
        assertThat(reloaded.getEquipmentType()).isEqualTo("Grader");
        assertThat(reloaded.getDprId()).isEqualTo(dprId);
    }

    @Test
    @DisplayName("DprEquipment defaults shift to DAY when builder omits it")
    @Transactional
    void equipmentDefaultsToDay() {
        DprEquipment e = DprEquipment.builder()
                .dprId(UUID.randomUUID())
                .equipmentType("Excavator")
                .build();

        DprEquipment saved = equipmentRepository.saveAndFlush(e);
        DprEquipment reloaded = equipmentRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getShift()).isEqualTo(Shift.DAY);
    }

    @Test
    @DisplayName("DprManpower with Shift.NIGHT round-trips through JPA")
    @Transactional
    void manpowerNightShiftPersists() {
        UUID dprId = UUID.randomUUID();
        DprManpower m = DprManpower.builder()
                .dprId(dprId)
                .trade("Mason")
                .shift(Shift.NIGHT)
                .build();

        DprManpower saved = manpowerRepository.saveAndFlush(m);
        UUID id = saved.getId();
        assertThat(id).isNotNull();

        manpowerRepository.flush();
        DprManpower reloaded = manpowerRepository.findById(id).orElseThrow();
        assertThat(reloaded.getShift()).isEqualTo(Shift.NIGHT);
        assertThat(reloaded.getTrade()).isEqualTo("Mason");
    }
}
