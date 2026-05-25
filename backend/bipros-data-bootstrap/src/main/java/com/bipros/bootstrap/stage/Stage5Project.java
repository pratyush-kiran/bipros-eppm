package com.bipros.bootstrap.stage;

import com.bipros.bootstrap.BootstrapApplication;
import com.bipros.bootstrap.Stage;
import com.bipros.bootstrap.input.ParsedDatasetStore;
import com.bipros.bootstrap.model.ParsedDataset;
import com.bipros.calendar.domain.model.Calendar;
import com.bipros.calendar.domain.repository.CalendarRepository;
import com.bipros.project.domain.model.EpsNode;
import com.bipros.project.domain.model.ObsNode;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.ProjectStatus;
import com.bipros.project.domain.repository.EpsNodeRepository;
import com.bipros.project.domain.repository.ObsNodeRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class Stage5Project implements Stage {

    private static final String DEFAULT_EPS_CODE = "DEFAULT-EPS";
    private static final String DEFAULT_OBS_CODE = "DEFAULT-OBS";

    private final ParsedDatasetStore store;
    private final EpsNodeRepository epsNodeRepository;
    private final ObsNodeRepository obsNodeRepository;
    private final ProjectRepository projectRepository;
    private final CalendarRepository calendarRepository;

    public static void main(String[] args) {
        BootstrapApplication.runStage(Stage5Project.class, args);
    }

    @Override
    @Transactional
    public void run() {
        ParsedDataset d = store.load();
        ParsedDataset.ProjectInfo p = d.project;
        if (p == null || p.code == null || p.code.isBlank()) {
            throw new IllegalStateException("ParsedDataset.project.code is required");
        }

        UUID epsId = resolveOrCreateEps();
        UUID obsId = resolveOrCreateObs();
        UUID calendarId = resolveCalendarId();

        Optional<Project> existing = projectRepository.findByCode(p.code);
        Project project = existing.orElseGet(Project::new);
        boolean isNew = existing.isEmpty();

        project.setCode(p.code);
        project.setName(p.name);
        project.setDescription(p.description);
        project.setPlannedStartDate(p.plannedStart);
        project.setPlannedFinishDate(p.plannedFinish);
        project.setStatus(ProjectStatus.PLANNED);
        project.setEpsNodeId(epsId);
        project.setObsNodeId(obsId);
        project.setBudgetCurrency(p.currency != null ? p.currency : "INR");
        project.setCalendarId(calendarId);
        project.setFromLocation(p.fromLocation);
        project.setToLocation(p.toLocation);
        project.setFromChainageM(p.fromChainageM);
        project.setToChainageM(p.toChainageM);
        project.setMorthCode(p.morthCode);
        project.setCategory(p.category);

        Project saved = projectRepository.save(project);
        log.info("Stage 5 — project {} ({}): {}", saved.getCode(), saved.getName(),
                isNew ? "inserted" : "updated");
    }

    private UUID resolveOrCreateEps() {
        return epsNodeRepository.findByParentIdIsNullOrderBySortOrder().stream()
                .filter(n -> DEFAULT_EPS_CODE.equals(n.getCode()))
                .findFirst()
                .map(EpsNode::getId)
                .orElseGet(() -> {
                    EpsNode n = new EpsNode();
                    n.setCode(DEFAULT_EPS_CODE);
                    n.setName("Default EPS");
                    n.setParentId(null);
                    n.setSortOrder(0);
                    EpsNode saved = epsNodeRepository.save(n);
                    log.info("Stage 5 — created default EPS node {}", DEFAULT_EPS_CODE);
                    return saved.getId();
                });
    }

    private UUID resolveOrCreateObs() {
        return obsNodeRepository.findByParentIdIsNullOrderBySortOrder().stream()
                .filter(n -> DEFAULT_OBS_CODE.equals(n.getCode()))
                .findFirst()
                .map(ObsNode::getId)
                .orElseGet(() -> {
                    ObsNode n = new ObsNode();
                    n.setCode(DEFAULT_OBS_CODE);
                    n.setName("Default OBS");
                    n.setParentId(null);
                    n.setSortOrder(0);
                    ObsNode saved = obsNodeRepository.save(n);
                    log.info("Stage 5 — created default OBS node {}", DEFAULT_OBS_CODE);
                    return saved.getId();
                });
    }

    private UUID resolveCalendarId() {
        Optional<Calendar> match = calendarRepository.findAll().stream()
                .filter(c -> c.getStandardWorkDaysPerWeek() != null
                        && c.getStandardWorkDaysPerWeek() == 6
                        && c.getStandardWorkHoursPerDay() != null
                        && c.getStandardWorkHoursPerDay() == 8.0)
                .findFirst();
        if (match.isEmpty()) {
            log.warn("Stage 5 — no 6-day / 8-hour calendar found; leaving calendarId null");
            return null;
        }
        return match.get().getId();
    }
}
