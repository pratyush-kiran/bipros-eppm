package com.bipros.api.controller.admin;

import com.bipros.api.dto.DataHealthResponse;
import com.bipros.api.dto.RepairReport;
import com.bipros.api.dto.RepairRequest;
import com.bipros.api.service.ProjectBudgetCorrectionService;
import com.bipros.api.service.ProjectDataRepairService;
import com.bipros.common.dto.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectDataRepairControllerTest {

  @Mock
  ProjectDataRepairService service;

  @Mock
  ProjectBudgetCorrectionService budgetCorrectionService;

  ProjectDataRepairController controller;

  @BeforeEach
  void setUp() {
    controller = new ProjectDataRepairController(service, budgetCorrectionService);
  }

  // --- helpers ---

  private static DataHealthResponse sampleHealth(UUID projectId) {
    return new DataHealthResponse(
        projectId,
        /* dprTotal */       42,
        /* supervisorIssues */ 3,
        /* nullCategoryRows */ 1,
        /* unitMismatches */   2,
        /* resourceLessDprs */ 4,
        /* boqItems */         10,
        /* boqWithStaleRate */ 0,
        /* minDate */          LocalDate.of(2026, 1, 1),
        /* maxDate */          LocalDate.of(2026, 3, 31));
  }

  private static RepairReport sampleReport(boolean dryRun, DataHealthResponse health) {
    return new RepairReport(
        dryRun,
        List.of("SUPERVISORS", "RATE_LABELS"),
        Map.of("SUPERVISORS", 3, "RATE_LABELS", 1),
        health,
        health);
  }

  // --- tests ---

  @Test
  void dataHealth_returns200_withServiceResult() {
    UUID projectId = UUID.randomUUID();
    DataHealthResponse expected = sampleHealth(projectId);
    when(service.diagnose(projectId)).thenReturn(expected);

    ResponseEntity<ApiResponse<DataHealthResponse>> response = controller.dataHealth(projectId);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    ApiResponse<DataHealthResponse> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.data()).isSameAs(expected);
    assertThat(body.error()).isNull();
    verify(service).diagnose(projectId);
  }

  @Test
  void repair_withRequest_delegatesToServiceAndWrapsReport() {
    UUID projectId = UUID.randomUUID();
    RepairRequest req = new RepairRequest();
    req.setDryRun(false);
    req.setPhases(List.of("SUPERVISORS"));

    DataHealthResponse health = sampleHealth(projectId);
    RepairReport expected = sampleReport(false, health);
    when(service.repair(projectId, req)).thenReturn(expected);

    ResponseEntity<ApiResponse<RepairReport>> response = controller.repair(projectId, req);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    ApiResponse<RepairReport> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.data()).isSameAs(expected);
    assertThat(body.error()).isNull();
    verify(service).repair(projectId, req);
  }

  @Test
  void repair_withNullBody_invokesServiceWithDefaultDryRunTrue() {
    UUID projectId = UUID.randomUUID();
    DataHealthResponse health = sampleHealth(projectId);
    RepairReport stubReport = sampleReport(true, health);

    ArgumentCaptor<RepairRequest> captor = ArgumentCaptor.forClass(RepairRequest.class);
    when(service.repair(org.mockito.ArgumentMatchers.eq(projectId), captor.capture()))
        .thenReturn(stubReport);

    ResponseEntity<ApiResponse<RepairReport>> response = controller.repair(projectId, null);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    RepairRequest captured = captor.getValue();
    assertThat(captured).isNotNull();
    assertThat(captured.isDryRun()).isTrue();
  }
}
