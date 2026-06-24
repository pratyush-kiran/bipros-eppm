package com.bipros.activity.application.listener;

import com.bipros.activity.application.percent.BoqProgressGuard;
import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.PercentCompleteType;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.common.util.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivityDurationPercentCompleteListenerGateTest {

  @Mock ActivityRepository activityRepository;
  @Mock AuditService auditService;
  @Mock BoqProgressGuard boqProgressGuard;

  @Test
  void skipsWhenActivityIsBoqDriven() {
    UUID id = UUID.randomUUID();
    Activity a = new Activity();
    a.setId(id);
    a.setPercentCompleteType(PercentCompleteType.DURATION);
    a.setActualStartDate(LocalDate.of(2026, 4, 1));
    a.setOriginalDuration(30.0);
    when(activityRepository.findById(id)).thenReturn(Optional.of(a));
    when(boqProgressGuard.isBoqDriven(id)).thenReturn(true);

    var listener = new ActivityDurationPercentCompleteListener(
        activityRepository, new PercentCompleteCalculator(), auditService, boqProgressGuard);

    DprSubmittedEvent e = DprSubmittedEvent.withoutChildren(
        null, null, LocalDate.of(2026, 4, 15), null, null, null, null, null,
        DprMutationType.CREATED, id);

    listener.onDprSubmitted(e);

    verify(activityRepository, never()).save(any());
  }
}
