package com.bipros.bootstrap.stage;

import com.bipros.bootstrap.BootstrapApplication;
import com.bipros.bootstrap.Stage;
import com.bipros.bootstrap.input.ParsedDatasetStore;
import com.bipros.bootstrap.model.ParsedDataset;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class Stage6Wbs implements Stage {

    private final ParsedDatasetStore store;
    private final ProjectRepository projectRepository;
    private final WbsNodeRepository wbsNodeRepository;

    public static void main(String[] args) {
        BootstrapApplication.runStage(Stage6Wbs.class, args);
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

        int inserted = 0;
        int updated = 0;
        for (ParsedDataset.WbsChapter chapter : d.wbsChapters) {
            Optional<WbsNode> existing =
                    wbsNodeRepository.findByProjectIdAndCode(project.getId(), chapter.code);
            WbsNode node = existing.orElseGet(WbsNode::new);
            node.setProjectId(project.getId());
            node.setCode(chapter.code);
            node.setName(chapter.name);
            node.setParentId(null);
            node.setWbsLevel(1);
            node.setSortOrder(chapter.sortOrder);
            node.setPlannedStart(project.getPlannedStartDate());
            node.setPlannedFinish(project.getPlannedFinishDate());
            wbsNodeRepository.save(node);
            if (existing.isPresent()) updated++; else inserted++;
        }
        log.info("Stage 6 — WBS nodes for {}: {} inserted, {} updated",
                project.getCode(), inserted, updated);
    }
}
