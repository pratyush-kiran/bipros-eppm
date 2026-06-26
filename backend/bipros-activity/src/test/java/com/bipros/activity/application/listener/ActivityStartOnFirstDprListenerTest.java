package com.bipros.activity.application.listener;

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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivityStartOnFirstDprListenerTest {

    @Mock ActivityRepository activityRepository;
    @Mock DailyProgressReportRepository dprRepository;
    @Mock AuditService auditService;
    @InjectMocks ActivityStartOnFirstDprListener listener;

    private DprSubmittedEvent createdEvent(UUID activityId) {
        return DprSubmittedEvent.withoutChildren(
                null, null, LocalDate.of(2026, 6, 1), null, null, null, null, null,
                DprMutationType.CREATED, activityId);
    }

    @Test
    void doesNotBootstrapWhenNoApprovedDprExists() {
        UUID id = UUID.randomUUID();
        Activity a = notStarted(id);
        when(activityRepository.findById(id)).thenReturn(Optional.of(a));
        when(dprRepository.findEarliestApprovedReportDateForActivity(id)).thenReturn(Optional.empty());

        listener.onDprSubmitted(createdEvent(id));

        verify(activityRepository, never()).save(any());
    }

    @Test
    void bootstrapsToInProgressWhenFirstApprovedDprExists() {
        UUID id = UUID.randomUUID();
        Activity a = notStarted(id);
        LocalDate approvedDate = LocalDate.of(2026, 5, 15);
        when(activityRepository.findById(id)).thenReturn(Optional.of(a));
        when(dprRepository.findEarliestApprovedReportDateForActivity(id)).thenReturn(Optional.of(approvedDate));

        listener.onDprSubmitted(createdEvent(id));

        ArgumentCaptor<Activity> saved = ArgumentCaptor.forClass(Activity.class);
        verify(activityRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ActivityStatus.IN_PROGRESS);
        assertThat(saved.getValue().getActualStartDate()).isEqualTo(approvedDate);
    }

    @Test
    void doesNotOverwriteExistingActualStartDate() {
        UUID id = UUID.randomUUID();
        Activity a = notStarted(id);
        LocalDate existingStart = LocalDate.of(2026, 4, 1);
        a.setActualStartDate(existingStart);
        LocalDate approvedDate = LocalDate.of(2026, 5, 15);
        when(activityRepository.findById(id)).thenReturn(Optional.of(a));
        when(dprRepository.findEarliestApprovedReportDateForActivity(id)).thenReturn(Optional.of(approvedDate));

        listener.onDprSubmitted(createdEvent(id));

        ArgumentCaptor<Activity> saved = ArgumentCaptor.forClass(Activity.class);
        verify(activityRepository).save(saved.capture());
        assertThat(saved.getValue().getActualStartDate()).isEqualTo(existingStart);
    }

    @Test
    void idempotentWhenAlreadyInProgress() {
        UUID id = UUID.randomUUID();
        Activity a = new Activity();
        a.setId(id);
        a.setStatus(ActivityStatus.IN_PROGRESS);
        when(activityRepository.findById(id)).thenReturn(Optional.of(a));

        listener.onDprSubmitted(createdEvent(id));

        verify(activityRepository, never()).save(any());
        verify(dprRepository, never()).findEarliestApprovedReportDateForActivity(any());
    }

    @Test
    void ignoresDeleteEvents() {
        UUID id = UUID.randomUUID();
        DprSubmittedEvent deleteEvent = DprSubmittedEvent.withoutChildren(
                null, null, null, null, null, null, null, null,
                DprMutationType.DELETED, id);

        listener.onDprSubmitted(deleteEvent);

        verify(activityRepository, never()).findById(any());
    }

    @Test
    void skipsWhenActivityIdNull() {
        DprSubmittedEvent event = DprSubmittedEvent.withoutChildren(
                null, null, null, null, null, null, null, null,
                DprMutationType.CREATED, null);

        listener.onDprSubmitted(event);

        verify(activityRepository, never()).findById(any());
    }

    private Activity notStarted(UUID id) {
        Activity a = new Activity();
        a.setId(id);
        a.setStatus(ActivityStatus.NOT_STARTED);
        return a;
    }
}
