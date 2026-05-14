package com.bipros.project.application.service;

import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.project.application.dto.CreateQcTestTypeRequest;
import com.bipros.project.application.dto.QcTestTypeResponse;
import com.bipros.project.application.dto.UpdateQcTestTypeRequest;
import com.bipros.project.domain.model.QcTestType;
import com.bipros.project.domain.repository.QcTestTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class QcTestTypeService {

    private final QcTestTypeRepository repository;

    @Transactional(readOnly = true)
    public List<QcTestTypeResponse> listByProject(UUID projectId) {
        return repository.findAllByProjectIdAndActiveTrue(projectId).stream()
            .map(QcTestTypeResponse::from)
            .toList();
    }

    public QcTestTypeResponse create(UUID projectId, CreateQcTestTypeRequest request) {
        QcTestType type = QcTestType.builder()
            .projectId(projectId)
            .name(request.name())
            .unit(request.unit())
            .ircThreshold(request.ircThreshold())
            .active(true)
            .build();
        QcTestType saved = repository.save(type);
        log.info("Created QC test type '{}' for project {}", saved.getName(), projectId);
        return QcTestTypeResponse.from(saved);
    }

    public QcTestTypeResponse update(UUID projectId, UUID typeId, UpdateQcTestTypeRequest request) {
        QcTestType type = findType(projectId, typeId);
        type.setName(request.name());
        type.setUnit(request.unit());
        type.setIrcThreshold(request.ircThreshold());
        QcTestType saved = repository.save(type);
        log.info("Updated QC test type {} for project {}", typeId, projectId);
        return QcTestTypeResponse.from(saved);
    }

    public void delete(UUID projectId, UUID typeId) {
        QcTestType type = findType(projectId, typeId);
        type.setActive(false);
        repository.save(type);
        log.info("Soft-deleted QC test type {} for project {}", typeId, projectId);
    }

    private QcTestType findType(UUID projectId, UUID typeId) {
        return repository.findByIdAndProjectId(typeId, projectId)
            .orElseThrow(() -> new ResourceNotFoundException("QcTestType", typeId));
    }
}
