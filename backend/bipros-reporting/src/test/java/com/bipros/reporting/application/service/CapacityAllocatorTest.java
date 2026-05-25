package com.bipros.reporting.application.service;

import com.bipros.reporting.application.service.CapacityAllocator.AllocationResult;
import com.bipros.reporting.application.service.CapacityAllocator.RoleAlloc;
import com.bipros.reporting.application.service.CapacityAllocator.RoleInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@DisplayName("CapacityAllocator")
class CapacityAllocatorTest {

  private static final UUID HELPER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID FOREMAN = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID SUPER = UUID.fromString("00000000-0000-0000-0000-000000000003");

  private static final BigDecimal N_2353 = new BigDecimal("23.53");

  @Nested
  @DisplayName("SERIES — Unscoped, manpower wins")
  class SeriesUnscopedManpowerWins {

    @Test
    @DisplayName("workdone 540, MP 5×23.53=117.65, EQ 9×20.4=183.60 → MP side gets full 540, allocated by NOS")
    void allocatesFullQtyToWinningSideByHeadcount() {
      List<RoleInput> manpower = List.of(
          new RoleInput(HELPER,  3, N_2353),
          new RoleInput(FOREMAN, 1, N_2353),
          new RoleInput(SUPER,   1, N_2353));
      BigDecimal manpowerExpected = new BigDecimal("117.65"); // 5 × 23.53
      BigDecimal equipmentExpected = new BigDecimal("183.60"); // 9 × 20.4

      AllocationResult result = CapacityAllocator.allocate(
          /*sideExpected*/ manpowerExpected,
          /*otherSideExpected*/ equipmentExpected,
          /*qtyDone*/ new BigDecimal("540"),
          /*normCombination*/ "SERIES",
          /*roles*/ manpower);

      assertThat(result.hidden()).isFalse();
      assertThat(result.roleAllocations())
          .extracting(RoleAlloc::roleId, RoleAlloc::allocatedQty, RoleAlloc::normResolved)
          .containsExactly(
              tuple(HELPER,  new BigDecimal("324.0000"), true),
              tuple(FOREMAN, new BigDecimal("108.0000"), true),
              tuple(SUPER,   new BigDecimal("108.0000"), true));
    }
  }

  @Nested
  @DisplayName("SERIES — equipment is losing side")
  class SeriesEquipmentHidden {

    @Test
    @DisplayName("MP wins (117.65 < 183.60) → equipment side is hidden")
    void equipmentSideHidden() {
      List<RoleInput> equipment = List.of(
          new RoleInput(UUID.randomUUID(), 4, new BigDecimal("20.4")),  // Excavator
          new RoleInput(UUID.randomUUID(), 1, new BigDecimal("20.4")),  // Tipper
          new RoleInput(UUID.randomUUID(), 1, new BigDecimal("20.4")),
          new RoleInput(UUID.randomUUID(), 1, new BigDecimal("20.4")),
          new RoleInput(UUID.randomUUID(), 1, new BigDecimal("20.4")),
          new RoleInput(UUID.randomUUID(), 1, new BigDecimal("20.4")));

      AllocationResult result = CapacityAllocator.allocate(
          /*sideExpected*/    new BigDecimal("183.60"),
          /*otherSide*/       new BigDecimal("117.65"),
          /*qtyDone*/         new BigDecimal("540"),
          /*combination*/     "SERIES",
          /*roles*/           equipment);

      assertThat(result.hidden()).isTrue();
      assertThat(result.roleAllocations()).hasSize(6);
      assertThat(result.roleAllocations())
          .allMatch(a -> a.allocatedQty() == null);
    }
  }

  @Nested
  @DisplayName("PARALLEL")
  class Parallel {

    @Test
    @DisplayName("MP 117.65 / EQ 183.60 / qty 540 → MP gets 540×117.65/301.25 ≈ 210.87 split by NOS")
    void manpowerShare() {
      List<RoleInput> manpower = List.of(
          new RoleInput(HELPER,  3, N_2353),
          new RoleInput(FOREMAN, 1, N_2353),
          new RoleInput(SUPER,   1, N_2353));

      AllocationResult result = CapacityAllocator.allocate(
          new BigDecimal("117.65"), new BigDecimal("183.60"),
          new BigDecimal("540"), "PARALLEL", manpower);

      assertThat(result.hidden()).isFalse();
      // Side share = 540 × 117.65 / 301.25 = 210.8913 (4dp HALF_UP)
      // Helper share = 210.8913 × 3/5 = 126.5348
      // Foreman/Super share = 210.8913 × 1/5 = 42.1783
      assertThat(result.roleAllocations().get(0).allocatedQty())
          .isEqualByComparingTo(new BigDecimal("126.5348"));
      assertThat(result.roleAllocations().get(1).allocatedQty())
          .isEqualByComparingTo(new BigDecimal("42.1783"));
      assertThat(result.roleAllocations().get(2).allocatedQty())
          .isEqualByComparingTo(new BigDecimal("42.1783"));
    }
  }

  @Nested
  @DisplayName("SUBSTITUTE")
  class Substitute {

    @Test
    @DisplayName("EQ larger (183.60 > 117.65) → EQ gets full 540; MP is hidden")
    void equipmentWins() {
      List<RoleInput> manpower = List.of(new RoleInput(HELPER, 5, N_2353));

      AllocationResult mp = CapacityAllocator.allocate(
          new BigDecimal("117.65"), new BigDecimal("183.60"),
          new BigDecimal("540"), "SUBSTITUTE", manpower);
      assertThat(mp.hidden()).isTrue();

      AllocationResult eq = CapacityAllocator.allocate(
          new BigDecimal("183.60"), new BigDecimal("117.65"),
          new BigDecimal("540"), "SUBSTITUTE",
          List.of(new RoleInput(UUID.randomUUID(), 9, new BigDecimal("20.4"))));
      assertThat(eq.hidden()).isFalse();
      assertThat(eq.roleAllocations().get(0).allocatedQty())
          .isEqualByComparingTo(new BigDecimal("540.0000"));
    }
  }

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {

    @Test
    @DisplayName("single-side activity (other side expected = 0) → full qty to this side")
    void singleSide() {
      AllocationResult result = CapacityAllocator.allocate(
          new BigDecimal("100"), BigDecimal.ZERO,
          new BigDecimal("250"), "SERIES",
          List.of(new RoleInput(HELPER, 4, new BigDecimal("25"))));
      assertThat(result.hidden()).isFalse();
      assertThat(result.roleAllocations().get(0).allocatedQty())
          .isEqualByComparingTo(new BigDecimal("250.0000"));
    }

    @Test
    @DisplayName("role with no norm (untracked) is skipped from allocation; tracked roles take 100%")
    void untrackedRoleSkipped() {
      // Only Helper has a norm. Foreman & Supervisor are untracked.
      List<RoleInput> manpower = List.of(
          new RoleInput(HELPER,  3, N_2353),
          new RoleInput(FOREMAN, 1, null),
          new RoleInput(SUPER,   1, null));

      AllocationResult result = CapacityAllocator.allocate(
          new BigDecimal("70.59"),     // 3 × 23.53 — only tracked roles contribute to side expected
          new BigDecimal("100"),       // some equipment side expected
          new BigDecimal("540"),
          "SERIES",                    // 70.59 < 100 → MP wins → full 540 to MP side
          manpower);

      assertThat(result.hidden()).isFalse();
      // Only Helper is tracked → Helper gets the entire 540.
      assertThat(result.roleAllocations().get(0).allocatedQty())
          .isEqualByComparingTo(new BigDecimal("540.0000"));
      assertThat(result.roleAllocations().get(0).normResolved()).isTrue();
      assertThat(result.roleAllocations().get(1).allocatedQty()).isNull();
      assertThat(result.roleAllocations().get(1).normResolved()).isFalse();
      assertThat(result.roleAllocations().get(2).allocatedQty()).isNull();
      assertThat(result.roleAllocations().get(2).normResolved()).isFalse();
    }

    @Test
    @DisplayName("zero qty done → all allocations are zero (not hidden)")
    void zeroQty() {
      AllocationResult result = CapacityAllocator.allocate(
          new BigDecimal("100"), new BigDecimal("200"),
          BigDecimal.ZERO, "SERIES",
          List.of(new RoleInput(HELPER, 3, N_2353)));
      assertThat(result.hidden()).isFalse();
      assertThat(result.roleAllocations().get(0).allocatedQty())
          .isEqualByComparingTo(BigDecimal.ZERO);
    }
  }
}
