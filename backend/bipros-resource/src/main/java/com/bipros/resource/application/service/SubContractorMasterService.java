package com.bipros.resource.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.resource.application.dto.SubContractorMasterResponse;
import com.bipros.resource.application.dto.SubContractorMasterWithMappingsRequest;
import com.bipros.resource.application.dto.SubContractorWorkActivityMappingRow;
import com.bipros.resource.domain.model.SubContractorWorkActivityMapping;
import com.bipros.resource.domain.model.SubContractorWorkType;
import com.bipros.resource.domain.model.master.SubContractorMaster;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.bipros.resource.domain.repository.SubContractorMasterRepository;
import com.bipros.resource.domain.repository.SubContractorWorkActivityMappingRepository;
import com.bipros.resource.domain.repository.SubContractorWorkTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class SubContractorMasterService {

  private final SubContractorMasterRepository repository;
  private final ActivitySubContractorAssignmentRepository assignmentRepository;
  private final SubContractorWorkActivityMappingRepository mappingRepository;
  private final SubContractorWorkTypeRepository workTypeRepository;
  private final AuditService auditService;

  @Transactional(readOnly = true)
  public List<SubContractorMasterResponse> list() {
    List<SubContractorMaster> masters = repository.findAll().stream()
        .sorted(displayOrder())
        .toList();
    List<UUID> ids = masters.stream().map(SubContractorMaster::getId).toList();
    Map<UUID, List<SubContractorWorkActivityMappingRow>> mappingsByMaster =
        mappingRepository.findAll().stream()
            .filter(m -> ids.contains(m.getSubContractorMasterId()))
            .collect(Collectors.groupingBy(
                SubContractorWorkActivityMapping::getSubContractorMasterId,
                Collectors.mapping(this::toRow, Collectors.toList())));
    return masters.stream()
        .map(m -> SubContractorMasterResponse.from(m,
            mappingsByMaster.getOrDefault(m.getId(), List.of())))
        .toList();
  }

  @Transactional(readOnly = true)
  public SubContractorMasterResponse get(UUID id) {
    SubContractorMaster m = repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("SubContractorMaster", id));
    List<SubContractorWorkActivityMappingRow> mappings =
        mappingRepository.findBySubContractorMasterIdOrderByWorkTypeNameAsc(id)
            .stream().map(this::toRow).toList();
    return SubContractorMasterResponse.from(m, mappings);
  }

  public SubContractorMasterResponse create(SubContractorMasterWithMappingsRequest req) {
    String code = req.code().trim();
    if (repository.findByCode(code).isPresent()) {
      throw new BusinessRuleException("DUPLICATE_SUB_CONTRACTOR_CODE",
          "Sub-Contractor with code " + code + " already exists");
    }
    SubContractorMaster saved = repository.save(SubContractorMaster.builder()
        .code(code)
        .name(req.name().trim())
        .location(req.location())
        .primaryContactName(req.primaryContactName())
        .primaryContactNumber(req.primaryContactNumber())
        .remarks(req.remarks())
        .active(req.active() == null ? Boolean.TRUE : req.active())
        .build());
    saveMappings(saved.getId(), req.workActivityMappings());
    SubContractorMasterResponse response = get(saved.getId());
    auditService.logCreate("SubContractorMaster", saved.getId(), response);
    return response;
  }

  public SubContractorMasterResponse update(UUID id, SubContractorMasterWithMappingsRequest req) {
    SubContractorMaster m = repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("SubContractorMaster", id));
    String code = req.code().trim();
    if (!m.getCode().equals(code) && repository.findByCode(code).isPresent()) {
      throw new BusinessRuleException("DUPLICATE_SUB_CONTRACTOR_CODE",
          "Sub-Contractor with code " + code + " already exists");
    }
    m.setCode(code);
    m.setName(req.name().trim());
    m.setLocation(req.location());
    m.setPrimaryContactName(req.primaryContactName());
    m.setPrimaryContactNumber(req.primaryContactNumber());
    m.setRemarks(req.remarks());
    if (req.active() != null) m.setActive(req.active());
    repository.save(m);
    mappingRepository.deleteBySubContractorMasterId(id);
    saveMappings(id, req.workActivityMappings());
    SubContractorMasterResponse response = get(id);
    auditService.logUpdate("SubContractorMaster", id, "subContractor", null, response);
    return response;
  }

  public void delete(UUID id) {
    SubContractorMaster m = repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("SubContractorMaster", id));
    long usage = assignmentRepository.countBySubContractorMasterId(id);
    if (usage > 0) {
      throw new BusinessRuleException("SUB_CONTRACTOR_IN_USE",
          "Sub-Contractor '" + m.getName() + "' is referenced by " + usage
              + " activity assignment(s) and cannot be deleted");
    }
    mappingRepository.deleteBySubContractorMasterId(id);
    repository.delete(m);
    auditService.logDelete("SubContractorMaster", id);
  }

  private void saveMappings(UUID masterId, List<SubContractorWorkActivityMappingRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return;
    }
    List<SubContractorWorkActivityMapping> entities = rows.stream().map(row -> {
      SubContractorWorkType wt = workTypeRepository.findById(row.scWorkTypeId())
          .orElseThrow(() -> new ResourceNotFoundException("SubContractorWorkType", row.scWorkTypeId()));
      return SubContractorWorkActivityMapping.builder()
          .subContractorMasterId(masterId)
          .scWorkTypeId(row.scWorkTypeId())
          .workTypeName(wt.getName())
          .unit(row.unit() != null ? row.unit() : wt.getDefaultUnit())
          .ratePerUnit(row.ratePerUnit())
          .outputPerDay(row.outputPerDay())
          .build();
    }).toList();
    mappingRepository.saveAll(entities);
  }

  private SubContractorWorkActivityMappingRow toRow(SubContractorWorkActivityMapping e) {
    return new SubContractorWorkActivityMappingRow(
        e.getId(), e.getScWorkTypeId(), e.getWorkTypeName(),
        e.getUnit(), e.getRatePerUnit(), e.getOutputPerDay());
  }

  private static Comparator<SubContractorMaster> displayOrder() {
    Comparator<SubContractorMaster> byActive = Comparator.comparing(
        SubContractorMaster::getActive,
        Comparator.nullsLast(Comparator.reverseOrder()));
    return byActive.thenComparing(
        SubContractorMaster::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
  }
}
