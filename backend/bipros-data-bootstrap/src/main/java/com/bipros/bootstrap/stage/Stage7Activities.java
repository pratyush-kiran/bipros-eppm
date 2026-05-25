package com.bipros.bootstrap.stage;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.ActivityType;
import com.bipros.activity.domain.model.DurationType;
import com.bipros.activity.domain.model.PercentCompleteType;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.bootstrap.BootstrapApplication;
import com.bipros.bootstrap.Stage;
import com.bipros.bootstrap.input.ParsedDatasetStore;
import com.bipros.bootstrap.model.ParsedDataset;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class Stage7Activities implements Stage {

    private final ParsedDatasetStore store;
    private final ProjectRepository projectRepository;
    private final WbsNodeRepository wbsNodeRepository;
    private final ActivityRepository activityRepository;
    private final WorkActivityRepository workActivityRepository;

    public static void main(String[] args) {
        BootstrapApplication.runStage(Stage7Activities.class, args);
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

        List<WbsNode> wbsNodes = wbsNodeRepository.findByProjectIdOrderBySortOrder(project.getId());
        Map<String, UUID> wbsByCode = new HashMap<>();
        for (WbsNode w : wbsNodes) {
            wbsByCode.put(w.getCode(), w.getId());
        }

        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        for (ParsedDataset.ActivityInfo info : d.activities) {
            UUID wbsId = wbsByCode.get(info.wbsChapterCode);
            if (wbsId == null) {
                log.warn("Stage 7 — activity {} references unknown WBS chapter {}, skipping",
                        info.code, info.wbsChapterCode);
                skipped++;
                continue;
            }
            UUID workActivityId = null;
            if (info.workActivityCode != null) {
                Optional<WorkActivity> wa = workActivityRepository.findByCode(info.workActivityCode);
                if (wa.isEmpty()) {
                    log.warn("Stage 7 — activity {} references unknown work activity {}",
                            info.code, info.workActivityCode);
                } else {
                    workActivityId = wa.get().getId();
                }
            }

            Optional<Activity> existing =
                    activityRepository.findByProjectIdAndCode(project.getId(), info.code);
            Activity activity = existing.orElseGet(Activity::new);

            activity.setProjectId(project.getId());
            activity.setCode(info.code);
            activity.setName(info.name);
            activity.setWbsNodeId(wbsId);
            activity.setWorkActivityId(workActivityId);
            activity.setPlannedStartDate(info.plannedStart);
            activity.setPlannedFinishDate(info.plannedFinish);
            activity.setOriginalDuration(computeDuration(info));
            activity.setStatus(ActivityStatus.NOT_STARTED);
            activity.setEditStatus(ActivityEditStatus.DRAFT);
            activity.setActivityType(ActivityType.TASK_DEPENDENT);
            activity.setDurationType(DurationType.FIXED_DURATION_AND_UNITS);
            activity.setPercentCompleteType(PercentCompleteType.DURATION);
            activity.setChainageFromM(info.chainageFromM);
            activity.setChainageToM(info.chainageToM);

            activityRepository.save(activity);
            if (existing.isPresent()) updated++; else inserted++;
        }
        log.info("Stage 7 — activities for {}: {} inserted, {} updated, {} skipped",
                project.getCode(), inserted, updated, skipped);
    }

    private Double computeDuration(ParsedDataset.ActivityInfo info) {
        if (info.plannedStart == null || info.plannedFinish == null) return null;
        long days = ChronoUnit.DAYS.between(info.plannedStart, info.plannedFinish);
        return (double) days;
    }
}
