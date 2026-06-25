package com.bipros.project.application.service;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.assertThat;

class DailyProgressReportServiceRollupWrapperTest {
  @Test
  void publicWrappersExist() throws Exception {
    Method a = DailyProgressReportService.class.getMethod(
        "recomputeActivityResourceActuals", java.util.UUID.class);
    Method b = DailyProgressReportService.class.getMethod(
        "recomputeScActualsForAssignments", java.util.Set.class);
    assertThat(java.lang.reflect.Modifier.isPublic(a.getModifiers())).isTrue();
    assertThat(java.lang.reflect.Modifier.isPublic(b.getModifiers())).isTrue();
  }
}
