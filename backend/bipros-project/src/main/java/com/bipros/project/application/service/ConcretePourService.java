package com.bipros.project.application.service;

import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.project.application.dto.ConcretePourResponse;
import com.bipros.project.application.dto.CreateConcretePourRequest;
import com.bipros.project.domain.model.ConcretePour;
import com.bipros.project.domain.repository.ConcretePourRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD + aggregation service for {@link ConcretePour}. Filtering on list is done via in-memory
 * stream operations on the project-scoped result set — the dataset per project is small enough
 * (1-2k pours) that this beats the boilerplate of a JPA Specification. Switch to a Specification
 * if a single project's pour count climbs into the tens of thousands.
 */
@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class ConcretePourService {

  private final ConcretePourRepository repository;

  public ConcretePourResponse create(UUID projectId, CreateConcretePourRequest req) {
    ConcretePour entity = toEntity(projectId, req);
    ConcretePour saved = repository.save(entity);
    return ConcretePourResponse.from(saved);
  }

  public List<ConcretePourResponse> createBulk(UUID projectId, List<CreateConcretePourRequest> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<ConcretePour> entities = new ArrayList<>(rows.size());
    for (CreateConcretePourRequest req : rows) {
      entities.add(toEntity(projectId, req));
    }
    return repository.saveAll(entities).stream()
        .map(ConcretePourResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public Page<ConcretePourResponse> list(
      UUID projectId, LocalDate from, LocalDate to, String site, Pageable pageable) {
    List<ConcretePour> all;
    if (from != null && to != null) {
      all = repository.findByProjectIdAndPourDateBetween(projectId, from, to);
    } else if (site != null && !site.isBlank()) {
      all = repository.findByProjectIdAndSite(projectId, site);
    } else {
      all = repository.findByProjectIdOrderByPourDateAscIdAsc(projectId);
    }

    List<ConcretePour> filtered = all.stream()
        .filter(p -> from == null || !p.getPourDate().isBefore(from))
        .filter(p -> to == null || !p.getPourDate().isAfter(to))
        .filter(p -> site == null || site.isBlank() || site.equalsIgnoreCase(p.getSite()))
        .sorted(Comparator.comparing(ConcretePour::getPourDate)
            .thenComparing(ConcretePour::getId))
        .toList();

    int total = filtered.size();
    int offset = (int) Math.min(pageable.getOffset(), total);
    int end = Math.min(offset + pageable.getPageSize(), total);
    List<ConcretePourResponse> page = filtered.subList(offset, end).stream()
        .map(ConcretePourResponse::from)
        .toList();
    return new PageImpl<>(page, pageable, total);
  }

  @Transactional(readOnly = true)
  public ConcretePourResponse get(UUID projectId, UUID id) {
    return ConcretePourResponse.from(find(projectId, id));
  }

  public void delete(UUID projectId, UUID id) {
    ConcretePour pour = find(projectId, id);
    repository.delete(pour);
  }

  @Transactional(readOnly = true)
  public Map<String, BigDecimal> totalsByGrade(UUID projectId) {
    return toMap(repository.sumByGrade(projectId));
  }

  @Transactional(readOnly = true)
  public Map<String, BigDecimal> totalsBySite(UUID projectId) {
    return toMap(repository.sumBySite(projectId));
  }

  // ─── helpers ────────────────────────────────────────────────────────────────────

  private ConcretePour find(UUID projectId, UUID id) {
    ConcretePour pour = repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("ConcretePour", id));
    if (!projectId.equals(pour.getProjectId())) {
      throw new ResourceNotFoundException("ConcretePour", id);
    }
    return pour;
  }

  private ConcretePour toEntity(UUID projectId, CreateConcretePourRequest req) {
    return ConcretePour.builder()
        .projectId(projectId)
        .pourDate(req.pourDate())
        .site(req.site())
        .plantName(req.plantName())
        .chainageM(req.chainageM())
        .structure(req.structure())
        .element(req.element())
        .gradeCode(req.gradeCode())
        .quantityM3(req.quantityM3())
        .slumpValue(req.slumpValue())
        .temperatureC(req.temperatureC())
        .sectionLabel(req.sectionLabel())
        .supervisorUserId(req.supervisorUserId())
        .dprId(req.dprId())
        .remarks(req.remarks())
        .build();
  }

  private Map<String, BigDecimal> toMap(List<Object[]> rows) {
    Map<String, BigDecimal> totals = new LinkedHashMap<>();
    for (Object[] row : rows) {
      String key = row[0] == null ? "" : row[0].toString();
      BigDecimal value = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
      totals.put(key, value);
    }
    return totals;
  }
}
