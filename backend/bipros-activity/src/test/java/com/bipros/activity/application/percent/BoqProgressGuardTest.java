package com.bipros.activity.application.percent;

import com.bipros.project.domain.repository.DailyProgressReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoqProgressGuardTest {

  @Mock DailyProgressReportRepository dprRepository;
  @InjectMocks BoqProgressGuard guard;

  @Test
  void isBoqDrivenWhenLinkedBoqQtyPositive() {
    UUID id = UUID.randomUUID();
    when(dprRepository.sumLinkedBoqQty(id)).thenReturn(new BigDecimal("1000"));
    assertThat(guard.isBoqDriven(id)).isTrue();
  }

  @Test
  void notBoqDrivenWhenZeroOrNull() {
    UUID id = UUID.randomUUID();
    when(dprRepository.sumLinkedBoqQty(id)).thenReturn(BigDecimal.ZERO);
    assertThat(guard.isBoqDriven(id)).isFalse();
  }
}
