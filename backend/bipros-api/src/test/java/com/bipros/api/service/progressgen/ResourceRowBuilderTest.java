package com.bipros.api.service.progressgen;

import static org.assertj.core.api.Assertions.assertThat;

import com.bipros.resource.domain.model.ResourceAssignment;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceRowBuilderTest {

  private final ResourceRowBuilder builder = new ResourceRowBuilder();

  @Test
  void scalesManpowerHeadcountByFractionMinOne() {
    ResourceAssignment mp = ResourceAssignment.builder()
        .activityId(UUID.randomUUID()).roleId(UUID.randomUUID())
        .manpowerRoleRateId(UUID.randomUUID()).headcount(3).build();
    var rows = builder.build(List.of(mp), 0.5, 8);
    assertThat(rows.manpower()).hasSize(1);
    assertThat(rows.manpower().get(0).nos()).isEqualTo(2);        // round(3*0.5)=2 (HALF_UP)
    assertThat(rows.manpower().get(0).workingHours()).isEqualByComparingTo("8");
    assertThat(rows.manpower().get(0).manpowerRoleRateId()).isEqualTo(mp.getManpowerRoleRateId());
    assertThat(rows.manpower().get(0).unitRate()).isNull();       // server snapshots
  }

  @Test
  void scalesMaterialQuantity() {
    ResourceAssignment mat = ResourceAssignment.builder()
        .activityId(UUID.randomUUID()).roleId(UUID.randomUUID())
        .materialRoleVariantId(UUID.randomUUID()).quantity(new BigDecimal("100")).unit("MT").build();
    var rows = builder.build(List.of(mat), 0.4, 8);
    assertThat(rows.materials()).hasSize(1);
    assertThat(rows.materials().get(0).quantity()).isEqualByComparingTo("40.0");
  }
}
