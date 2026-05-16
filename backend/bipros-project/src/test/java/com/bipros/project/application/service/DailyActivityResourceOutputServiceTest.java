package com.bipros.project.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.CreateDailyActivityResourceOutputRequest;
import com.bipros.project.application.dto.DailyActivityResourceOutputResponse;
import com.bipros.project.domain.model.DailyActivityResourceOutput;
import com.bipros.project.domain.repository.DailyActivityResourceOutputRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DailyActivityResourceOutputService")
class DailyActivityResourceOutputServiceTest {

  @Mock private DailyActivityResourceOutputRepository repository;
  @Mock private ProjectRepository projectRepository;
  @Mock private AuditService auditService;
  @Mock private EntityManager entityManager;
  @Mock private Query rollupQuery;
  @Mock private ApplicationEventPublisher eventPublisher;

  private DailyActivityResourceOutputService service;

  private final UUID projectId = UUID.randomUUID();
  private final UUID activityId = UUID.randomUUID();
  private final UUID resourceId = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    service = new DailyActivityResourceOutputService(
        repository, projectRepository, auditService, eventPublisher);
    Field emField = DailyActivityResourceOutputService.class.getDeclaredField("em");
    emField.setAccessible(true);
    emField.set(service, entityManager);
    lenient().when(projectRepository.existsById(projectId)).thenReturn(true);
    // Default: any native query returns the rollup-mock with a chain that yields executeUpdate=1.
    // Tests that need the unit-fallback (resolveUnitFromActivity) override this.
    lenient().when(entityManager.createNativeQuery(contains("UPDATE resource.resource_assignments")))
        .thenReturn(rollupQuery);
    lenient().when(rollupQuery.setParameter(any(String.class), any())).thenReturn(rollupQuery);
    lenient().when(rollupQuery.executeUpdate()).thenReturn(1);
  }

  @Test
  @DisplayName("derives daysWorked from hoursWorked / 8 when daysWorked is null")
  void derivesDaysFromHours() {
    when(repository.save(any())).thenAnswer(inv -> {
      DailyActivityResourceOutput row = inv.getArgument(0);
      row.setId(UUID.randomUUID());
      return row;
    });

    CreateDailyActivityResourceOutputRequest req = new CreateDailyActivityResourceOutputRequest(
        LocalDate.of(2026, 5, 1), activityId, resourceId,
        new BigDecimal("3500"), "Sqm", 8.0, null, "ok");

    DailyActivityResourceOutputResponse resp = service.create(projectId, req);

    assertThat(resp.daysWorked()).isEqualTo(1.0);
    assertThat(resp.hoursWorked()).isEqualTo(8.0);
    assertThat(resp.unit()).isEqualTo("Sqm");

    // Save → rollup: native UPDATE fires once with the (project, activity, resource) triple.
    verify(entityManager).createNativeQuery(contains("UPDATE resource.resource_assignments"));
    verify(rollupQuery).setParameter(eq("projectId"), eq(projectId));
    verify(rollupQuery).setParameter(eq("activityId"), eq(activityId));
    verify(rollupQuery).setParameter(eq("resourceId"), eq(resourceId));
    verify(rollupQuery, times(1)).executeUpdate();
  }

  @Test
  @DisplayName("delete also recomputes the assignment rollup")
  void deleteRecomputesRollup() {
    UUID rowId = UUID.randomUUID();
    DailyActivityResourceOutput existing = DailyActivityResourceOutput.builder()
        .projectId(projectId).outputDate(LocalDate.of(2026, 5, 1))
        .activityId(activityId).resourceId(resourceId)
        .qtyExecuted(BigDecimal.TEN).unit("Sqm").build();
    existing.setId(rowId);
    when(repository.findById(rowId)).thenReturn(Optional.of(existing));

    service.delete(projectId, rowId);

    verify(repository).delete(existing);
    verify(rollupQuery).setParameter(eq("projectId"), eq(projectId));
    verify(rollupQuery).setParameter(eq("activityId"), eq(activityId));
    verify(rollupQuery).setParameter(eq("resourceId"), eq(resourceId));
    verify(rollupQuery, times(1)).executeUpdate();
  }

  @Test
  @DisplayName("rejects duplicate (project, date, activity, resource)")
  void rejectsDuplicate() {
    DailyActivityResourceOutput existing = DailyActivityResourceOutput.builder()
        .projectId(projectId).outputDate(LocalDate.of(2026, 5, 1))
        .activityId(activityId).resourceId(resourceId)
        .qtyExecuted(BigDecimal.ONE).unit("Sqm").build();
    existing.setId(UUID.randomUUID());
    when(repository
        .findFirstByProjectIdAndOutputDateAndActivityIdAndResourceIdAndSource(
            projectId, LocalDate.of(2026, 5, 1), activityId, resourceId, "MANUAL"))
        .thenReturn(Optional.of(existing));

    CreateDailyActivityResourceOutputRequest req = new CreateDailyActivityResourceOutputRequest(
        LocalDate.of(2026, 5, 1), activityId, resourceId,
        new BigDecimal("100"), "Sqm", null, null, null);

    assertThatThrownBy(() -> service.create(projectId, req))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  @DisplayName("404s when project doesn't exist")
  void unknownProject() {
    UUID otherProject = UUID.randomUUID();
    when(projectRepository.existsById(otherProject)).thenReturn(false);

    CreateDailyActivityResourceOutputRequest req = new CreateDailyActivityResourceOutputRequest(
        LocalDate.now(), activityId, resourceId, BigDecimal.ONE, "Sqm", null, null, null);

    assertThatThrownBy(() -> service.create(otherProject, req))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("blocks save when unit is blank and the activity has no defaultUnit fallback")
  void unitRequiredWhenNoFallback() {
    // EntityManager returns no row → resolveUnitFromActivity catches the NoResultException and returns null
    when(entityManager.createNativeQuery(any(String.class)))
        .thenThrow(new RuntimeException("no result"));

    CreateDailyActivityResourceOutputRequest req = new CreateDailyActivityResourceOutputRequest(
        LocalDate.now(), activityId, resourceId, BigDecimal.ONE, null, null, null, null);

    assertThatThrownBy(() -> service.create(projectId, req))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessageContaining("unit is required");
  }
}
