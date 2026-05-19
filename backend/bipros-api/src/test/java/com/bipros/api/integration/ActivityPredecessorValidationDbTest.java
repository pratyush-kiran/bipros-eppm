package com.bipros.api.integration;

import com.bipros.activity.application.dto.UpdateActivityRequest;
import com.bipros.activity.application.service.ActivityService;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityRelationship;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.RelationshipType;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Predecessor-validation tests that run against the local dev PostgreSQL
 * database (bipros-postgres on 5432).  These do NOT use Testcontainers;
 * they assume {@code docker compose up -d} has been run.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
@Rollback
@DisplayName("ActivityService predecessor validation — real DB")
class ActivityPredecessorValidationDbTest {

    @Autowired
    private ActivityService activityService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ActivityRelationshipRepository relationshipRepository;

    private UUID projectId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Blocks updateActivity when successor percentComplete > 0 and FS predecessor not finished")
    void updateActivity_blocksProgressOnUnfinishedFsPredecessor() {
        Activity pred = activity("ACT-7.1", "Predecessor");
        Activity succ = activity("ACT-7.2", "Successor");
        fsRel(pred, succ, 2.0);

        UpdateActivityRequest req = new UpdateActivityRequest(
            null, null, null, null, null, null,
            null, null, null,
            12.0, null, null, null,
            null, null, null, null, null, null, null, null, null, null
        , null, null, null, null, null);

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
            () -> activityService.updateActivity(succ.getId(), req));

        assertThat(ex.getRuleCode()).isEqualTo("PREDECESSOR_NOT_SATISFIED");
    }

    @Test
    @DisplayName("Allows updateActivity when successor percentComplete == 0 and no actual dates")
    void updateActivity_allowsZeroPercentWithUnfinishedPredecessor() {
        Activity pred = activity("ACT-7.1b", "Predecessor");
        Activity succ = activity("ACT-7.2b", "Successor");
        fsRel(pred, succ, 0.0);

        UpdateActivityRequest req = new UpdateActivityRequest(
            "Renamed", null, null, null, null, null,
            null, null, null,
            0.0, null, null, null,
            null, null, null, null, null, null, null, null, null, null
        , null, null, null, null, null);

        // Should succeed — no progress claimed
        activityService.updateActivity(succ.getId(), req);

        Activity updated = activityRepository.findById(succ.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Renamed");
        assertThat(updated.getPercentComplete()).isZero();
    }

    @Test
    @DisplayName("Blocks updateProgress when FS predecessor not finished")
    void updateProgress_blocksOnUnfinishedFsPredecessor() {
        Activity pred = activity("ACT-7.1c", "Predecessor");
        Activity succ = activity("ACT-7.2c", "Successor");
        fsRel(pred, succ, 2.0);

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
            () -> activityService.updateProgress(succ.getId(), 12.0, null, null));

        assertThat(ex.getRuleCode()).isEqualTo("PREDECESSOR_NOT_SATISFIED");
    }

    @Test
    @DisplayName("Allows updateActivity once FS predecessor has finished")
    void updateActivity_allowsAfterPredecessorFinishes() {
        Activity pred = activity("ACT-7.1d", "Predecessor");
        pred.setActualStartDate(java.time.LocalDate.of(2026, 4, 1));
        pred.setActualFinishDate(java.time.LocalDate.of(2026, 4, 10));
        pred.setPercentComplete(100.0);
        pred.setStatus(ActivityStatus.COMPLETED);
        activityRepository.save(pred);

        Activity succ = activity("ACT-7.2d", "Successor");
        fsRel(pred, succ, 2.0);

        UpdateActivityRequest req = new UpdateActivityRequest(
            null, null, null, null, null, null,
            null, null, null,
            12.0, null, java.time.LocalDate.of(2026, 4, 14), null,
            null, null, null, null, null, null, null, null, null, null
        , null, null, null, null, null);

        activityService.updateActivity(succ.getId(), req);

        Activity updated = activityRepository.findById(succ.getId()).orElseThrow();
        assertThat(updated.getPercentComplete()).isEqualTo(12.0);
    }

    private Activity activity(String code, String name) {
        Activity a = new Activity();
        a.setProjectId(projectId);
        a.setWbsNodeId(UUID.randomUUID());
        a.setCode(code);
        a.setName(name);
        a.setStatus(ActivityStatus.NOT_STARTED);
        a.setPercentComplete(0.0);
        return activityRepository.save(a);
    }

    private void fsRel(Activity pred, Activity succ, double lag) {
        ActivityRelationship r = new ActivityRelationship();
        r.setProjectId(projectId);
        r.setPredecessorActivityId(pred.getId());
        r.setSuccessorActivityId(succ.getId());
        r.setRelationshipType(RelationshipType.FINISH_TO_START);
        r.setLag(lag);
        r.setIsExternal(false);
        relationshipRepository.save(r);
    }
}
