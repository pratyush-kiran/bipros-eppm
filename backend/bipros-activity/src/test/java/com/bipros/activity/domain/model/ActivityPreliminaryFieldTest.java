package com.bipros.activity.domain.model;

import com.bipros.activity.application.dto.ActivityResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 (BOQ {@code isPreliminary} flag) — TDD-first. These tests pin down the contract
 * for the new flag on the canonical BOQ activity entity:
 * <ul>
 *   <li>defaults to {@code false} on a fresh in-memory instance,</li>
 *   <li>round-trips cleanly through the Lombok-generated getter / setter,</li>
 *   <li>is exposed verbatim on {@link ActivityResponse} so the admin UI can read & write it.</li>
 * </ul>
 *
 * <p>Persistence (Hibernate ddl-auto on dev, Liquibase changeset
 * {@code 2026-05-add-activity-preliminary.xml} on prod) is enforced by the
 * {@code @Column(name = "is_preliminary", nullable = false)} annotation on the entity field —
 * a unit test asserting the column annotation would tautologically test the mapping framework,
 * so we exercise the only behaviour our code owns: the default and the field's visibility in the
 * DTO contract that the activity admin page consumes.
 */
@DisplayName("Activity.preliminary (BOQ Section 1 flag)")
class ActivityPreliminaryFieldTest {

  @Test
  @DisplayName("defaults to false on a fresh entity")
  void defaultsToFalseOnFreshEntity() {
    Activity activity = new Activity();
    assertThat(activity.isPreliminary())
        .as("New BOQ activity must default to non-preliminary so existing rows that haven't been "
            + "touched since the migration ran behave as 'direct cost'")
        .isFalse();
  }

  @Test
  @DisplayName("round-trips through setter / getter")
  void roundTripsThroughSetterGetter() {
    Activity activity = new Activity();
    activity.setPreliminary(true);
    assertThat(activity.isPreliminary()).isTrue();

    activity.setPreliminary(false);
    assertThat(activity.isPreliminary()).isFalse();
  }

  @Test
  @DisplayName("is exposed on ActivityResponse.from(Activity)")
  void exposedOnActivityResponse() {
    Activity activity = new Activity();
    activity.setCode("BOQ-1.4.3(i)");
    activity.setName("Mobilisation & demobilisation");
    activity.setProjectId(java.util.UUID.randomUUID());
    activity.setWbsNodeId(java.util.UUID.randomUUID());
    activity.setPreliminary(true);

    ActivityResponse response = ActivityResponse.from(activity);

    assertThat(response.preliminary())
        .as("ActivityResponse must surface the preliminary flag so the admin UI can render the "
            + "'Preliminary item (BOQ Section 1 — mobilization, site setup, diversions, etc.)' "
            + "checkbox at its current state")
        .isTrue();
  }
}
