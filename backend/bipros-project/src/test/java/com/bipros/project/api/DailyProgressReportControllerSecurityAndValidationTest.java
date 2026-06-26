package com.bipros.project.api;

import com.bipros.project.application.dto.CreateDailyProgressReportRequest;
import com.bipros.project.application.dto.DprApprovalActionRequest;
import com.bipros.project.application.dto.UpdateDailyProgressReportRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards two regressions:
 *
 * <ul>
 *   <li><b>DA-EDGE-7 (CRITICAL)</b> — Cross-project DPR data leak. GET/list endpoints on
 *       {@link DailyProgressReportController} previously had no {@code @PreAuthorize}, so any
 *       authenticated user could read another project's DPRs by passing its {@code projectId}.
 *       This test verifies every read endpoint now carries the
 *       {@code @projectAccess.hasProjectPermission(#projectId, 'DPR.READ')} guard so the
 *       cross-project bypass cannot regress.
 *   <li><b>DA-EDGE-3 (HIGH)</b> — Future-dated DPRs accepted. {@code reportDate} must now carry
 *       {@code @PastOrPresent}; phantom progress would otherwise land in next-day roll-ups and
 *       distort DBS / Performance D/W/M / EV.
 * </ul>
 */
@DisplayName("DailyProgressReportController — DA-EDGE-7 access guards + DA-EDGE-3 date validation")
class DailyProgressReportControllerSecurityAndValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) factory.close();
    }

    // ─── DA-EDGE-7 — every read endpoint has a project-scoped @PreAuthorize ─────────

    @Test
    @DisplayName("GET /dpr (list) requires DPR.READ on the path projectId")
    void listRequiresProjectReadPermission() throws Exception {
        assertProjectReadGuarded(method("list", UUID.class, LocalDate.class, LocalDate.class, String.class, LocalDate.class, int.class));
    }

    @Test
    @DisplayName("GET /dpr/{id} requires DPR.READ on the path projectId")
    void getRequiresProjectReadPermission() throws Exception {
        assertProjectReadGuarded(method("get", UUID.class, UUID.class));
    }

    @Test
    @DisplayName("GET /dpr/supervisors-used requires DPR.READ on the path projectId")
    void supervisorsUsedRequiresProjectReadPermission() throws Exception {
        assertProjectReadGuarded(method("supervisorsUsed", UUID.class, LocalDate.class, LocalDate.class));
    }

    @Test
    @DisplayName("GET /dpr/{id}/photos requires DPR.READ on the path projectId")
    void listPhotosRequiresProjectReadPermission() throws Exception {
        assertProjectReadGuarded(method("listPhotos", UUID.class, UUID.class));
    }

    @Test
    @DisplayName("GET /dpr/{id}/photos/{photoId} requires DPR.READ on the path projectId")
    void getPhotoRequiresProjectReadPermission() throws Exception {
        assertProjectReadGuarded(method("getPhoto", UUID.class, UUID.class, UUID.class));
    }

    @Test
    @DisplayName("POST /dpr/activities/{activityId}/productivity-preview requires DPR.READ")
    void productivityPreviewRequiresProjectReadPermission() throws Exception {
        assertProjectReadGuarded(method("productivityPreview", UUID.class, UUID.class,
                com.bipros.project.application.dto.ProductivityPreviewRequest.class));
    }

    // ─── Approval action endpoints require DPR.APPROVE ──────────────────────────────

    @Test
    @DisplayName("POST /dpr/{id}/approve requires DPR.APPROVE on the path projectId")
    void approveRequiresDprApprovePermission() throws Exception {
        assertProjectApproveGuarded(method("approve", UUID.class, UUID.class, DprApprovalActionRequest.class));
    }

    @Test
    @DisplayName("POST /dpr/{id}/reject requires DPR.APPROVE on the path projectId")
    void rejectRequiresDprApprovePermission() throws Exception {
        assertProjectApproveGuarded(method("reject", UUID.class, UUID.class, DprApprovalActionRequest.class));
    }

    @Test
    @DisplayName("POST /dpr/{id}/revoke requires DPR.APPROVE on the path projectId")
    void revokeRequiresDprApprovePermission() throws Exception {
        assertProjectApproveGuarded(method("revoke", UUID.class, UUID.class, DprApprovalActionRequest.class));
    }

    @Test
    @DisplayName("GET /dpr/approvals/pending requires DPR.APPROVE on the path projectId")
    void listPendingApprovalsRequiresDprApprovePermission() throws Exception {
        assertProjectApproveGuarded(method("listPendingApprovals", UUID.class));
    }

    @Test
    @DisplayName("GET /dpr/approvals/unassigned requires DPR.APPROVE on the path projectId")
    void listUnassignedPendingRequiresDprApprovePermission() throws Exception {
        assertProjectApproveGuarded(method("listUnassignedPending", UUID.class));
    }

    // ─── DA-EDGE-3 — reportDate rejects future dates on create + update ─────────────

    @Test
    @DisplayName("create: reportDate tomorrow violates @PastOrPresent with message about the future")
    void createRejectsFutureReportDate() {
        CreateDailyProgressReportRequest req = createReq(LocalDate.now().plusDays(1));
        Set<ConstraintViolation<CreateDailyProgressReportRequest>> violations = validator.validate(req);
        assertThat(violations)
                .as("future-dated DPR must trip the @PastOrPresent guard")
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("reportDate");
                    assertThat(v.getMessage()).contains("future");
                });
    }

    @Test
    @DisplayName("create: reportDate today is accepted (regression — present must still pass)")
    void createAcceptsTodayReportDate() {
        CreateDailyProgressReportRequest req = createReq(LocalDate.now());
        Set<ConstraintViolation<CreateDailyProgressReportRequest>> violations = validator.validate(req);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("reportDate");
    }

    @Test
    @DisplayName("create: reportDate in the past is accepted (regression)")
    void createAcceptsPastReportDate() {
        CreateDailyProgressReportRequest req = createReq(LocalDate.now().minusDays(7));
        Set<ConstraintViolation<CreateDailyProgressReportRequest>> violations = validator.validate(req);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("reportDate");
    }

    @Test
    @DisplayName("update: reportDate tomorrow violates @PastOrPresent")
    void updateRejectsFutureReportDate() {
        UpdateDailyProgressReportRequest req = updateReq(LocalDate.now().plusDays(1));
        Set<ConstraintViolation<UpdateDailyProgressReportRequest>> violations = validator.validate(req);
        assertThat(violations)
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("reportDate");
                    assertThat(v.getMessage()).contains("future");
                });
    }

    // ─── helpers ────────────────────────────────────────────────────────────────────

    private static Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return DailyProgressReportController.class.getMethod(name, params);
    }

    private static void assertProjectReadGuarded(Method m) {
        PreAuthorize ann = m.getAnnotation(PreAuthorize.class);
        assertThat(ann)
                .as("%s must be annotated with @PreAuthorize to prevent cross-project data leak", m.getName())
                .isNotNull();
        assertThat(ann.value())
                .as("%s must scope the check to the request's #projectId with DPR.READ", m.getName())
                .contains("projectAccess.hasProjectPermission(#projectId,")
                .contains("DPR.READ");
    }

    private static void assertProjectApproveGuarded(Method m) {
        PreAuthorize ann = m.getAnnotation(PreAuthorize.class);
        assertThat(ann)
                .as("%s must be annotated with @PreAuthorize", m.getName())
                .isNotNull();
        assertThat(ann.value())
                .as("%s must scope the check to #projectId with DPR.APPROVE", m.getName())
                .contains("projectAccess.hasProjectPermission(#projectId,")
                .contains("DPR.APPROVE");
    }

    private static CreateDailyProgressReportRequest createReq(LocalDate reportDate) {
        return new CreateDailyProgressReportRequest(
                reportDate,
                null,
                "Supervisor",
                null, null,
                null,
                "Bench Cutting",
                null, null, null,
                "Cum",
                new BigDecimal("10.0"),
                null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static UpdateDailyProgressReportRequest updateReq(LocalDate reportDate) {
        return new UpdateDailyProgressReportRequest(
                reportDate,
                null,
                "Supervisor",
                null, null,
                null,
                "Bench Cutting",
                null, null, null,
                "Cum",
                new BigDecimal("10.0"),
                null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
