package com.bipros.scheduling.application.service;

import com.bipros.scheduling.domain.model.ScheduleResult;
import com.bipros.scheduling.domain.model.ScheduleStatus;
import com.bipros.scheduling.domain.model.SchedulingOption;
import com.bipros.scheduling.domain.repository.ScheduleResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleFailureRecorder — recordFailure")
class ScheduleFailureRecorderTest {

  @Mock private ScheduleResultRepository scheduleResultRepository;

  private ScheduleFailureRecorder recorder;

  @BeforeEach
  void setUp() {
    recorder = new ScheduleFailureRecorder(scheduleResultRepository);
    when(scheduleResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  @DisplayName("saves a ScheduleResult with status=FAILED and non-null required fields")
  void savesFailedRow() {
    UUID projectId = UUID.randomUUID();
    SchedulingOption option = SchedulingOption.RETAINED_LOGIC;

    recorder.recordFailure(projectId, option, 1.23, "some error");

    ArgumentCaptor<ScheduleResult> captor = ArgumentCaptor.forClass(ScheduleResult.class);
    verify(scheduleResultRepository).save(captor.capture());

    ScheduleResult saved = captor.getValue();
    assertEquals(ScheduleStatus.FAILED, saved.getStatus());
    assertEquals(projectId, saved.getProjectId());
    assertEquals(option, saved.getSchedulingOption());
    assertNotNull(saved.getDataDate());
    assertNotNull(saved.getCalculatedAt());
    assertEquals(1.23, saved.getDurationSeconds(), 0.001);
    assertEquals(0, saved.getTotalActivities());
    assertEquals(0, saved.getCriticalActivities());
  }

  @Test
  @DisplayName("defaults schedulingOption to RETAINED_LOGIC when null is passed")
  void defaultsOptionWhenNull() {
    UUID projectId = UUID.randomUUID();

    recorder.recordFailure(projectId, null, 0.5, "error");

    ArgumentCaptor<ScheduleResult> captor = ArgumentCaptor.forClass(ScheduleResult.class);
    verify(scheduleResultRepository).save(captor.capture());

    assertEquals(SchedulingOption.RETAINED_LOGIC, captor.getValue().getSchedulingOption());
  }
}
