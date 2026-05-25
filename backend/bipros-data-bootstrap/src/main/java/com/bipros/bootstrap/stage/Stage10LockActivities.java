package com.bipros.bootstrap.stage;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.bootstrap.BootstrapApplication;
import com.bipros.bootstrap.Stage;
import com.bipros.bootstrap.input.ParsedDatasetStore;
import com.bipros.bootstrap.model.ParsedDataset;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Stage 10 — flip the project's activities from {@code DRAFT} to {@code LOCKED} so
 * DPRs can be submitted against them. DPR submission rejects DRAFT activities.
 *
 * <p>Idempotent: activities already LOCKED are skipped.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class Stage10LockActivities implements Stage {

    private final ParsedDatasetStore store;
    private final ProjectRepository projectRepository;
    private final ActivityRepository activityRepository;

    public static void main(String[] args) {
        BootstrapApplication.runStage(Stage10LockActivities.class, args);
    }

    @Override
    @Transactional
    public void run() {
        ParsedDataset d = store.load();
        if (d.project == null || d.project.code == null) {
            throw new IllegalStateException("ParsedDataset.project.code is required");
        }
        Project project = projectRepository.findByCode(d.project.code)
                .orElseThrow(() -> new IllegalStateException(
                        "Project " + d.project.code + " not found — run Stage 5 first"));

        List<Activity> activities = activityRepository.findByProjectId(project.getId());
        int locked = 0;
        int already = 0;
        for (Activity a : activities) {
            if (a.getEditStatus() == ActivityEditStatus.LOCKED) {
                already++;
                continue;
            }
            a.setEditStatus(ActivityEditStatus.LOCKED);
            activityRepository.save(a);
            locked++;
        }
        log.info("Stage 10 — project {}: {} activities locked, {} already LOCKED",
                project.getCode(), locked, already);
    }
}
