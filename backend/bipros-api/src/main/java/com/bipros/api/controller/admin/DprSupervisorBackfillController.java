package com.bipros.api.controller.admin;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceStatus;
import com.bipros.resource.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Admin tool for Phase 7.4: produces a CSV listing every distinct legacy {@code supervisorName}
 * value found on existing DPR rows together with the best-guess Resource match. The admin
 * reviews the CSV and runs targeted SQL UPDATEs to set {@code supervisor_resource_id}; we
 * deliberately do NOT auto-match because string heuristics on names like "Mr. K. Rao" / "Rao K"
 * produce false positives. Unmatched legacy rows stay legacy — the DPR form's free-text "Other"
 * branch covers them on edit.
 */
@RestController
@RequestMapping("/v1/admin/dpr-supervisor-backfill")
@PreAuthorize("hasPermission(null, 'ADMIN_USER.UPDATE')")
@RequiredArgsConstructor
public class DprSupervisorBackfillController {

  private static final String LABOR_TYPE_CODE = "LABOR";

  private final DailyProgressReportRepository dprRepository;
  private final ResourceRepository resourceRepository;

  @PostMapping
  @Transactional(readOnly = true)
  public ResponseEntity<String> exportBackfillCsv() {
    String csv = buildCsv();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv"))
        .header("Content-Disposition", "attachment; filename=\"dpr-supervisor-backfill.csv\"")
        .body(csv);
  }

  /** Convenience GET — same payload, returned as JSON for ad-hoc use from a browser. */
  @GetMapping
  @Transactional(readOnly = true)
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> previewBackfill() {
    return ResponseEntity.ok(ApiResponse.ok(buildPreview()));
  }

  private String buildCsv() {
    StringBuilder sb = new StringBuilder();
    sb.append("legacy_supervisor_name,best_match_resource_id,best_match_resource_name,best_match_role_code,occurrence_count\n");
    for (Map<String, Object> row : buildPreview()) {
      sb.append(csvField(String.valueOf(row.getOrDefault("legacy_supervisor_name", ""))));
      sb.append(',').append(csvField(String.valueOf(row.getOrDefault("best_match_resource_id", ""))));
      sb.append(',').append(csvField(String.valueOf(row.getOrDefault("best_match_resource_name", ""))));
      sb.append(',').append(csvField(String.valueOf(row.getOrDefault("best_match_role_code", ""))));
      sb.append(',').append(row.getOrDefault("occurrence_count", 0));
      sb.append('\n');
    }
    return sb.toString();
  }

  private List<Map<String, Object>> buildPreview() {
    Map<String, Long> nameCounts = new java.util.TreeMap<>();
    for (DailyProgressReport dpr : dprRepository.findAll()) {
      // Only consider rows that have NOT been backfilled yet.
      if (dpr.getSupervisorUserId() != null) continue;
      String name = dpr.getSupervisorName();
      if (name == null || name.isBlank()) continue;
      nameCounts.merge(name.trim(), 1L, Long::sum);
    }

    // Match against every active Labor resource — broader pool gives the human reviewer
    // more candidate names to verify against legacy free-text supervisorName values.
    List<Resource> candidates = resourceRepository.findByResourceType_CodeAndStatus(
        LABOR_TYPE_CODE, ResourceStatus.ACTIVE);

    List<Map<String, Object>> out = new java.util.ArrayList<>(nameCounts.size());
    for (Map.Entry<String, Long> e : nameCounts.entrySet()) {
      Resource match = bestMatch(e.getKey(), candidates);
      Map<String, Object> row = new java.util.LinkedHashMap<>();
      row.put("legacy_supervisor_name", e.getKey());
      row.put("best_match_resource_id", match != null ? match.getId().toString() : "");
      row.put("best_match_resource_name", match != null ? match.getName() : "");
      row.put("best_match_role_code", match != null && match.getRole() != null ? match.getRole().getCode() : "");
      row.put("occurrence_count", e.getValue());
      out.add(row);
    }
    return out;
  }

  /**
   * Naive best-match: case-insensitive equality first, then case-insensitive contains.
   * Anything beyond is judgement: we surface the candidate, the human verifies and writes
   * the UPDATE. No fuzzy matching — false positives in this workflow rewrite history.
   */
  private Resource bestMatch(String legacyName, List<Resource> candidates) {
    String n = legacyName.toLowerCase();
    for (Resource r : candidates) {
      if (r.getName() != null && r.getName().equalsIgnoreCase(legacyName)) return r;
    }
    TreeSet<Resource> hits = new TreeSet<>((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    for (Resource r : candidates) {
      if (r.getName() != null && r.getName().toLowerCase().contains(n)) hits.add(r);
    }
    return hits.isEmpty() ? null : hits.first();
  }

  private static String csvField(String s) {
    if (s == null) return "";
    boolean needsQuote = s.contains(",") || s.contains("\"") || s.contains("\n");
    String escaped = s.replace("\"", "\"\"");
    return needsQuote ? "\"" + escaped + "\"" : escaped;
  }
}
