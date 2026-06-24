package com.bipros.scheduling.application.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the warning-humanisation helpers in {@link SchedulingService}.
 * No Spring context or DB required.
 */
class ReadableWarningsTest {

  @Test
  void replacesUuidWithCodeAndName() {
    UUID id = UUID.fromString("fc25d216-5e5f-4c09-a8aa-3a5d5934440b");
    Map<UUID, String> labels = Map.of(id, "ACT-1 - Camp work");
    List<String> input = List.of("Activity fc25d216-5e5f-4c09-a8aa-3a5d5934440b has no predecessors or successors");

    List<String> result = SchedulingService.toReadableWarnings(input, labels);

    assertThat(result).containsExactly("Activity ACT-1 - Camp work has no predecessors or successors");
  }

  @Test
  void leavesUnknownUuidUnchanged() {
    UUID knownId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    UUID unknownId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    Map<UUID, String> labels = Map.of(knownId, "ACT-1 - Known");
    List<String> input = List.of("Activity bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb has negative float");

    List<String> result = SchedulingService.toReadableWarnings(input, labels);

    assertThat(result).containsExactly("Activity bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb has negative float");
  }

  @Test
  void handlesMultipleUuidsInOneWarning() {
    UUID id1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID id2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    Map<UUID, String> labels = Map.of(
        id1, "ACT-1 - First",
        id2, "ACT-2 - Second"
    );
    List<String> input = List.of(
        "Relationship from 11111111-1111-1111-1111-111111111111 to 22222222-2222-2222-2222-222222222222 creates a cycle"
    );

    List<String> result = SchedulingService.toReadableWarnings(input, labels);

    assertThat(result).containsExactly(
        "Relationship from ACT-1 - First to ACT-2 - Second creates a cycle"
    );
  }

  @Test
  void emptyOrNullWarningsSafe() {
    Map<UUID, String> labels = Map.of();

    assertThat(SchedulingService.toReadableWarnings(null, labels)).isNull();
    assertThat(SchedulingService.toReadableWarnings(List.of(), labels)).isEmpty();
  }

  @Test
  void replaceUuidsLeavesNonUuidTextIntact() {
    Map<UUID, String> labels = Map.of();
    String warning = "No activities scheduled";

    assertThat(SchedulingService.replaceUuids(warning, labels)).isEqualTo("No activities scheduled");
  }
}
