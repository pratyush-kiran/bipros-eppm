package com.bipros.activity.application.listener;

import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivityProgressFromBoqListenerTest {

  @Mock ActivityRepository activityRepository;
  @Mock DailyProgressReportRepository dprRepository;
  @Mock AuditService auditService;

  private ActivityProgressFromBoqListener newListener() {
    return new ActivityProgressFromBoqListener(
        activityRepository, dprRepository, new PercentCompleteCalculator(), auditService);
  }

  private DprSubmittedEvent event(UUID activityId) {
    return DprSubmittedEvent.withoutChildren(
        null, null, null, null, null, null, null, null,
        DprMutationType.CREATED, activityId);
  }

  @Test
  void usesThisActivitysOwnWorkdoneNotTheBoqCrossTotal() {
    UUID id = UUID.randomUUID();
    Activity a = new Activity();
    a.setId(id);
    a.setActualStartDate(LocalDate.of(2026, 4, 1));
    when(activityRepository.findById(id)).thenReturn(Optional.of(a));
    // This activity did 250 of a shared 1000-qty BOQ → 25% (NOT the BOQ's combined 800/1000).
    when(dprRepository.sumActivityWorkdoneOnBoqApproved(id)).thenReturn(new BigDecimal("250"));
    when(dprRepository.sumLinkedBoqQtyApproved(id)).thenReturn(new BigDecimal("1000"));

    newListener().onDprSubmitted(event(id));

    ArgumentCaptor<Activity> saved = ArgumentCaptor.forClass(Activity.class);
    verify(activityRepository).save(saved.capture());
    assertThat(saved.getValue().getPercentComplete()).isEqualTo(25.0);
    assertThat(saved.getValue().getStatus()).isEqualTo(ActivityStatus.IN_PROGRESS);
  }

  @Test
  void completesAndSetsFinishAt100() {
    UUID id = UUID.randomUUID();
    Activity a = new Activity();
    a.setId(id);
    a.setActualStartDate(LocalDate.of(2026, 4, 1));
    when(activityRepository.findById(id)).thenReturn(Optional.of(a));
    when(dprRepository.sumActivityWorkdoneOnBoqApproved(id)).thenReturn(new BigDecimal("1000"));
    when(dprRepository.sumLinkedBoqQtyApproved(id)).thenReturn(new BigDecimal("1000"));

    newListener().onDprSubmitted(event(id));

    ArgumentCaptor<Activity> saved = ArgumentCaptor.forClass(Activity.class);
    verify(activityRepository).save(saved.capture());
    assertThat(saved.getValue().getPercentComplete()).isEqualTo(100.0);
    assertThat(saved.getValue().getStatus()).isEqualTo(ActivityStatus.COMPLETED);
    assertThat(saved.getValue().getActualFinishDate()).isNotNull();
  }

  @Test
  void skipsWhenNoBoqLinkedRows() {
    UUID id = UUID.randomUUID();
    Activity a = new Activity();
    a.setId(id);
    when(activityRepository.findById(id)).thenReturn(Optional.of(a));
    when(dprRepository.sumLinkedBoqQtyApproved(id)).thenReturn(BigDecimal.ZERO);

    newListener().onDprSubmitted(event(id));

    verify(activityRepository, never()).save(any());
  }
}
