package com.bipros.resource.application.service;

import com.bipros.resource.application.dto.SubContractorWorkTypeDto;
import com.bipros.resource.domain.model.SubContractorWorkType;
import com.bipros.resource.domain.repository.SubContractorWorkTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class SubContractorWorkTypeService {

  private final SubContractorWorkTypeRepository repository;

  @Transactional(readOnly = true)
  public List<SubContractorWorkTypeDto> search(String query) {
    if (query == null || query.isBlank()) {
      return listAll();
    }
    return repository.findTop20ByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(query.trim())
        .stream()
        .map(this::toDto)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<SubContractorWorkTypeDto> listAll() {
    return repository.findAllByActiveTrueOrderByNameAsc()
        .stream()
        .map(this::toDto)
        .toList();
  }

  public SubContractorWorkTypeDto findOrCreate(String name, String defaultUnit) {
    String trimmed = name.trim();
    Optional<SubContractorWorkType> existing = repository.findByNameIgnoreCase(trimmed);
    if (existing.isPresent()) {
      return toDto(existing.get());
    }
    SubContractorWorkType saved = repository.save(SubContractorWorkType.builder()
        .name(trimmed)
        .defaultUnit(defaultUnit)
        .active(true)
        .build());
    log.info("Created new SubContractorWorkType: id={}, name={}", saved.getId(), saved.getName());
    return toDto(saved);
  }

  private SubContractorWorkTypeDto toDto(SubContractorWorkType e) {
    return new SubContractorWorkTypeDto(e.getId(), e.getName(), e.getDefaultUnit());
  }
}
