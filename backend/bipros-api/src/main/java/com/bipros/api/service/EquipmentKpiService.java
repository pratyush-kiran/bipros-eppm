package com.bipros.api.service;

import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.EquipmentOwnership;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceEquipmentDetails;
import com.bipros.resource.domain.model.ResourceOwnership;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.role.EquipmentRoleVariant;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceEquipmentDetailsRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.bipros.resource.domain.repository.role.EquipmentRoleVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aggregator KPI service for equipment metrics. Reads exclusively from DPR + child rows
 * (daily_progress_reports, dpr_equipment) — both Daily Outputs (DAR) and the standalone
 * resource.equipment_logs ledger are deprecated and no longer consulted. Op/idle/breakdown
 * hours and fuel come from {@code dpr_equipment}; per-machine output qty is derived from the
 * parent DPR's {@code qty_executed} via a proportional split on equipment hours within the
 * same DPR.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EquipmentKpiService {

  private static final String EQUIPMENT_TYPE_CODE = "EQUIPMENT";
  private static final double DEFAULT_IDLE_THRESHOLD_HOURS = 2d;
  private static final int DEFAULT_SERVICE_LOOKAHEAD_DAYS = 7;

  private final ResourceRepository resourceRepository;
  private final ResourceEquipmentDetailsRepository equipmentDetailsRepository;
  private final DailyProgressReportRepository dprRepository;
  private final DprEquipmentRepository dprEquipmentRepository;
  private final EquipmentRoleVariantRepository equipmentRoleVariantRepository;
  private final ResourceRoleRepository resourceRoleRepository;
  private final ResourceAssignmentRepository resourceAssignmentRepository;

  // ---------- Response shapes (unchanged) ----------

  public record EquipmentKpiResponse(
      UUID projectId,
      LocalDate from,
      LocalDate to,
      List<UtilizationRow> utilization,
      List<IdleAlertRow> idleAlerts,
      List<FuelPerOutputRow> fuelPerOutput,
      List<AvailabilityPerformanceRow> availabilityPerformance,
      List<OwnedRentedSlice> ownedVsRented,
      List<ServiceDueRow> serviceDue,
      double mechanicalAvailabilityPct,
      double equipmentProductivityIndexPct,
      double idleMachineCostTotal,
      int actualNos,
      int plannedNos,
      double nosUtilizationPct
  ) {}

  /**
   * Per-machine row. {@code idleCost} (KPI 7.1) = idle_hours × Resource.cost_per_unit. The same
   * rate is used for OWNED and HIRED equipment until ownership-specific rate columns land
   * (locked Phase 2A fallback, 2026-05-08).
   */
  public record UtilizationRow(
      UUID resourceId,
      String resourceCode,
      String resourceName,
      double operatingHours,
      double idleHours,
      double breakdownHours,
      double utilizationPct,
      double mechanicalAvailabilityPct,
      double idleCost
  ) {}

  public record IdleAlertRow(
      UUID resourceId,
      String resourceCode,
      String resourceName,
      LocalDate logDate,
      double idleHours
  ) {}

  public record FuelPerOutputRow(
      UUID resourceId,
      String resourceCode,
      String resourceName,
      double fuelConsumed,
      double qtyExecuted,
      double fuelPerOutput
  ) {}

  /**
   * Per-machine row carrying availability + EPI + KPI 6.2 Machine Output Rate (qty/hr).
   * {@code outputRatePerHour} = attributed_qty ÷ Σ working_hours for the machine in the window.
   */
  public record AvailabilityPerformanceRow(
      UUID resourceId,
      String resourceCode,
      String resourceName,
      double availability,
      double performance,
      double outputRatePerHour
  ) {}

  public record OwnedRentedSlice(
      String ownershipType,
      double operatingHours,
      double cost
  ) {}

  public record ServiceDueRow(
      UUID resourceId,
      String resourceCode,
      String resourceName,
      LocalDate nextServiceDate,
      long daysUntilService
  ) {}

  /** Helper bag for one DPR-equipment row enriched with the parent DPR's date/qty share. */
  private record EnrichedDprEquipment(
      DprEquipment row,
      LocalDate logDate,
      double attributedQty
  ) {}

  /**
   * Synthetic machine identity for KPI rollups. {@code key} is the legacy {@code resource_id}
   * when present, otherwise the role-only {@code equipment_role_variant_id}. {@code costPerUnit}
   * is the per-(hour|day) rate used for idle-cost math (Resource.costPerUnit for legacy,
   * EquipmentRoleVariant.rate for role-only).
   */
  private record MachineDisplay(UUID key, String code, String name, double costPerUnit, double standardOutputPerDay) {}

  /** Returns the key used to identify a machine across DPR rows. */
  private static UUID pickMachineKey(DprEquipment row) {
    if (row.getResourceId() != null) return row.getResourceId();
    return row.getEquipmentRoleVariantId();
  }

  // ---------- Public API ----------

  @Transactional(readOnly = true)
  public EquipmentKpiResponse compute(UUID projectId, LocalDate from, LocalDate to) {
    List<DailyProgressReport> dprs = dprRepository
        .findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(projectId, from, to);
    if (dprs.isEmpty()) {
      return emptyResponse(projectId, from, to);
    }
    Set<UUID> dprIds = dprs.stream().map(DailyProgressReport::getId).collect(Collectors.toSet());
    List<DprEquipment> equipmentRows = dprEquipmentRepository.findByDprIdIn(dprIds);
    Map<UUID, List<DprEquipment>> equipmentByDpr = equipmentRows.stream()
        .collect(Collectors.groupingBy(DprEquipment::getDprId));

    // Enrich each dpr_equipment row with the parent DPR's date and an attributed qty
    // (parent qty × this row's hours ÷ Σ row hours for the same DPR).
    Map<UUID, DailyProgressReport> dprsById = dprs.stream()
        .collect(Collectors.toMap(DailyProgressReport::getId, d -> d, (a, b) -> a));
    List<EnrichedDprEquipment> enriched = enrichEquipmentRows(equipmentByDpr, dprsById);

    Set<UUID> resourceIds = equipmentRows.stream()
        .map(DprEquipment::getResourceId)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toSet());
    Map<UUID, Resource> resourcesById = resourceRepository.findAllById(resourceIds).stream()
        .collect(Collectors.toMap(Resource::getId, r -> r, (a, b) -> a));
    Map<UUID, ResourceEquipmentDetails> detailsById =
        equipmentDetailsRepository.findAllById(resourceIds).stream()
            .collect(Collectors.toMap(ResourceEquipmentDetails::getResourceId, d -> d, (a, b) -> a));

    // Build a display map keyed by "machine identity" — for legacy rows this is resource_id; for
    // role-only rows it's the equipment_role_variant_id. Drives the per-machine KPI rows.
    Map<UUID, MachineDisplay> machinesByKey = resolveMachineDisplay(equipmentRows, resourcesById);

    List<UtilizationRow> utilization = computeUtilization(enriched, machinesByKey);
    List<FuelPerOutputRow> fuelPerOutput = computeFuelPerOutput(enriched, machinesByKey);
    List<AvailabilityPerformanceRow> availPerf =
        computeAvailabilityPerformance(enriched, machinesByKey, detailsById);
    List<OwnedRentedSlice> ownedVsRented =
        computeOwnedVsRented(enriched, resourcesById, detailsById);
    List<ServiceDueRow> serviceDue = computeServiceDue(projectId, resourcesById, detailsById);

    double epiPct = computeEpiHeadline(availPerf);

    // Nos-based equipment utilisation: Σ actual nos (DPR) ÷ Σ planned headcount (assignments).
    // Mirrors the Workforce Deployment metric in ManpowerKpiService.
    List<ResourceAssignment> assignments = resourceAssignmentRepository.findByProjectId(projectId);
    int actualNos = equipmentRows.stream()
        .mapToInt(r -> r.getNos() != null ? r.getNos() : 0).sum();
    int plannedNos = assignments.stream()
        .filter(ra -> isEquipmentAssignment(ra, resourcesById))
        .mapToInt(ra -> ra.getHeadcount() != null ? ra.getHeadcount() : 0).sum();
    double nosUtilPct = plannedNos > 0 ? Math.min(1.0d, (double) actualNos / plannedNos) : 0d;

    // Idle / breakdown / mechanical-availability KPIs were removed when those DPR fields were
    // dropped from the supervisor UI. Return zero so back-compat clients still parse the shape.
    return new EquipmentKpiResponse(
        projectId, from, to, utilization, List.of(), fuelPerOutput,
        availPerf, ownedVsRented, serviceDue,
        0d,                          // mechanicalAvailabilityPct — n/a in nos × rate model
        round4(epiPct),
        0d,                          // idleMachineCostTotal — n/a in nos × rate model
        actualNos,
        plannedNos,
        round4(nosUtilPct));
  }

  /** True iff the assignment is equipment — role-only path OR legacy EQUIPMENT resource path. */
  private boolean isEquipmentAssignment(ResourceAssignment ra, Map<UUID, Resource> resourcesById) {
    if (ra.getEquipmentRoleVariantId() != null) return true;
    if (ra.getResourceId() == null) return false;
    Resource r = resourcesById.get(ra.getResourceId());
    if (r == null) {
      r = resourceRepository.findById(ra.getResourceId()).orElse(null);
      if (r != null) resourcesById.put(r.getId(), r);
    }
    return r != null && r.getResourceType() != null
        && EQUIPMENT_TYPE_CODE.equalsIgnoreCase(r.getResourceType().getCode());
  }

  // ---------- Enrichment ----------

  /**
   * Distribute each DPR's parent qty_executed across its equipment rows by working-hours share.
   * If the DPR has no working hours captured, every row gets 0 attributed qty (KPI tiles show
   * "—" for that machine).
   */
  private List<EnrichedDprEquipment> enrichEquipmentRows(
      Map<UUID, List<DprEquipment>> equipmentByDpr,
      Map<UUID, DailyProgressReport> dprsById) {
    List<EnrichedDprEquipment> out = new ArrayList<>();
    for (Map.Entry<UUID, List<DprEquipment>> e : equipmentByDpr.entrySet()) {
      DailyProgressReport dpr = dprsById.get(e.getKey());
      if (dpr == null) continue;
      double parentQty = dpr.getQtyExecuted() != null ? dpr.getQtyExecuted().doubleValue() : 0d;
      double totalHours = e.getValue().stream()
          .mapToDouble(r -> r.getWorkingHours() != null ? r.getWorkingHours().doubleValue() : 0d)
          .sum();
      for (DprEquipment row : e.getValue()) {
        double rowHours = row.getWorkingHours() != null ? row.getWorkingHours().doubleValue() : 0d;
        double attributedQty = totalHours > 0 ? parentQty * (rowHours / totalHours) : 0d;
        out.add(new EnrichedDprEquipment(row, dpr.getReportDate(), attributedQty));
      }
    }
    return out;
  }

  // ---------- Machine display resolution ----------

  /**
   * One MachineDisplay per machine identity seen in the DPR rows. Legacy rows surface as the
   * Resource (code/name/cost_per_unit); role-only rows surface as the EquipmentRoleVariant
   * (rate as cost_per_unit, "{role.name} – {make}/{model}" as display name).
   */
  private Map<UUID, MachineDisplay> resolveMachineDisplay(
      List<DprEquipment> equipmentRows,
      Map<UUID, Resource> resourcesById) {
    Map<UUID, MachineDisplay> out = new HashMap<>();
    Set<UUID> variantIds = new HashSet<>();
    for (DprEquipment row : equipmentRows) {
      UUID key = pickMachineKey(row);
      if (key == null) continue;
      if (out.containsKey(key)) continue;
      if (row.getResourceId() != null) {
        Resource r = resourcesById.get(row.getResourceId());
        double cpu = (r != null && r.getCostPerUnit() != null) ? r.getCostPerUnit().doubleValue() : 0d;
        out.put(key, new MachineDisplay(
            key,
            r != null ? r.getCode() : "?",
            r != null ? r.getName() : (row.getEquipmentType() != null ? row.getEquipmentType() : "Unknown"),
            cpu,
            0d));    // legacy resource standardOutput lives on ResourceEquipmentDetails, looked up at perf time
      } else if (row.getEquipmentRoleVariantId() != null) {
        variantIds.add(row.getEquipmentRoleVariantId());
      }
    }
    if (!variantIds.isEmpty()) {
      Map<UUID, EquipmentRoleVariant> variantsById =
          equipmentRoleVariantRepository.findAllById(variantIds).stream()
              .collect(Collectors.toMap(v -> v.getId(), v -> v, (a, b) -> a));
      Set<UUID> roleIds = variantsById.values().stream()
          .map(EquipmentRoleVariant::getRoleId)
          .filter(java.util.Objects::nonNull)
          .collect(Collectors.toSet());
      Map<UUID, ResourceRole> rolesById =
          resourceRoleRepository.findAllById(roleIds).stream()
              .collect(Collectors.toMap(ResourceRole::getId, r -> r, (a, b) -> a));
      for (DprEquipment row : equipmentRows) {
        if (row.getResourceId() != null) continue;
        UUID variantId = row.getEquipmentRoleVariantId();
        if (variantId == null || out.containsKey(variantId)) continue;
        EquipmentRoleVariant v = variantsById.get(variantId);
        if (v == null) {
          String fallback = row.getEquipmentType() != null ? row.getEquipmentType() : "Unknown";
          out.put(variantId, new MachineDisplay(variantId, fallback, fallback, 0d, 0d));
          continue;
        }
        ResourceRole role = rolesById.get(v.getRoleId());
        String roleName = role != null && role.getName() != null ? role.getName() : (row.getEquipmentType() != null ? row.getEquipmentType() : "Equipment");
        String name = roleName + " – " + v.getMake() + "/" + v.getModel();
        out.put(variantId, new MachineDisplay(
            variantId,
            v.getMake() + "/" + v.getModel(),
            name,
            v.getRate() != null ? v.getRate().doubleValue() : 0d,
            v.getStandardOutputPerDay() != null ? v.getStandardOutputPerDay().doubleValue() : 0d));
      }
    }
    return out;
  }

  // ---------- Utilisation + KPI 4.1 (Mechanical Availability per machine) ----------

  private List<UtilizationRow> computeUtilization(
      List<EnrichedDprEquipment> enriched,
      Map<UUID, MachineDisplay> machinesByKey) {
    Map<UUID, double[]> agg = new HashMap<>(); // [op, idle, breakdown]
    for (EnrichedDprEquipment e : enriched) {
      DprEquipment l = e.row();
      UUID key = pickMachineKey(l);
      if (key == null) continue;
      double[] acc = agg.computeIfAbsent(key, k -> new double[3]);
      if (l.getWorkingHours() != null) acc[0] += l.getWorkingHours().doubleValue();
      if (l.getIdleHours() != null) acc[1] += l.getIdleHours().doubleValue();
      if (l.getBreakdownHours() != null) acc[2] += l.getBreakdownHours().doubleValue();
    }
    List<UtilizationRow> rows = new ArrayList<>(agg.size());
    for (Map.Entry<UUID, double[]> e : agg.entrySet()) {
      MachineDisplay m = machinesByKey.get(e.getKey());
      double[] v = e.getValue();
      double total = v[0] + v[1] + v[2];
      double pct = total > 0 ? v[0] / total : 0d;
      double maPct = total > 0 ? (v[0] + v[1]) / total : 0d;
      double cpu = m != null ? m.costPerUnit() : 0d;
      double idleCost = v[1] * cpu;
      rows.add(new UtilizationRow(
          e.getKey(),
          m != null ? m.code() : "?",
          m != null ? m.name() : "Unknown",
          round2(v[0]), round2(v[1]), round2(v[2]),
          round4(pct), round4(maPct),
          round2(idleCost)));
    }
    rows.sort(Comparator.comparingDouble(UtilizationRow::utilizationPct).reversed());
    return rows;
  }

  private double computeMechanicalAvailabilityHeadline(List<UtilizationRow> rows) {
    List<UtilizationRow> usable = rows.stream()
        .filter(r -> r.operatingHours() + r.idleHours() + r.breakdownHours() > 0d)
        .toList();
    if (usable.isEmpty()) return 0d;
    return usable.stream().mapToDouble(UtilizationRow::mechanicalAvailabilityPct).average().orElse(0d);
  }

  private double computeEpiHeadline(List<AvailabilityPerformanceRow> rows) {
    List<AvailabilityPerformanceRow> usable = rows.stream()
        .filter(r -> r.performance() > 0d)
        .toList();
    if (usable.isEmpty()) return 0d;
    return usable.stream().mapToDouble(AvailabilityPerformanceRow::performance).average().orElse(0d);
  }

  // ---------- Idle-time alerts ----------

  private List<IdleAlertRow> computeIdleAlerts(
      List<EnrichedDprEquipment> enriched,
      Map<UUID, MachineDisplay> machinesByKey) {
    LocalDate latestDate = enriched.stream()
        .map(EnrichedDprEquipment::logDate)
        .filter(java.util.Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElse(null);
    if (latestDate == null) return List.of();
    return enriched.stream()
        .filter(e -> latestDate.equals(e.logDate()))
        .filter(e -> e.row().getIdleHours() != null
            && e.row().getIdleHours().doubleValue() > DEFAULT_IDLE_THRESHOLD_HOURS)
        .filter(e -> pickMachineKey(e.row()) != null)
        .sorted(Comparator.comparingDouble(
            (EnrichedDprEquipment x) -> x.row().getIdleHours().doubleValue()).reversed())
        .map(e -> {
          UUID key = pickMachineKey(e.row());
          MachineDisplay m = machinesByKey.get(key);
          return new IdleAlertRow(
              key,
              m != null ? m.code() : "?",
              m != null ? m.name() : "Unknown",
              e.logDate(),
              round2(e.row().getIdleHours().doubleValue()));
        })
        .toList();
  }

  // ---------- Fuel per output ----------

  private List<FuelPerOutputRow> computeFuelPerOutput(
      List<EnrichedDprEquipment> enriched,
      Map<UUID, MachineDisplay> machinesByKey) {
    Map<UUID, double[]> agg = new HashMap<>(); // [fuel, qty]
    for (EnrichedDprEquipment e : enriched) {
      DprEquipment l = e.row();
      UUID key = pickMachineKey(l);
      if (key == null) continue;
      if (l.getFuelLitres() == null || l.getFuelLitres().signum() <= 0) continue;
      double[] acc = agg.computeIfAbsent(key, k -> new double[2]);
      acc[0] += l.getFuelLitres().doubleValue();
      acc[1] += e.attributedQty();
    }
    List<FuelPerOutputRow> rows = new ArrayList<>(agg.size());
    for (Map.Entry<UUID, double[]> e : agg.entrySet()) {
      MachineDisplay m = machinesByKey.get(e.getKey());
      double fuel = e.getValue()[0];
      double qty = e.getValue()[1];
      double fpo = qty > 0 ? fuel / qty : 0d;
      rows.add(new FuelPerOutputRow(
          e.getKey(),
          m != null ? m.code() : "?",
          m != null ? m.name() : "Unknown",
          round2(fuel), round3(qty), round4(fpo)));
    }
    rows.sort(Comparator.comparingDouble(FuelPerOutputRow::fuelPerOutput).reversed());
    return rows;
  }

  // ---------- Availability + Performance (KPI 6.1 EPI input) ----------

  private List<AvailabilityPerformanceRow> computeAvailabilityPerformance(
      List<EnrichedDprEquipment> enriched,
      Map<UUID, MachineDisplay> machinesByKey,
      Map<UUID, ResourceEquipmentDetails> detailsById) {
    Map<UUID, double[]> agg = new HashMap<>(); // [op, idle, breakdown, qty]
    Map<UUID, Set<LocalDate>> daysSeen = new HashMap<>();
    for (EnrichedDprEquipment e : enriched) {
      DprEquipment l = e.row();
      UUID key = pickMachineKey(l);
      if (key == null) continue;
      double[] acc = agg.computeIfAbsent(key, k -> new double[4]);
      if (l.getWorkingHours() != null) acc[0] += l.getWorkingHours().doubleValue();
      if (l.getIdleHours() != null) acc[1] += l.getIdleHours().doubleValue();
      if (l.getBreakdownHours() != null) acc[2] += l.getBreakdownHours().doubleValue();
      acc[3] += e.attributedQty();
      if (e.logDate() != null) {
        daysSeen.computeIfAbsent(key, k -> new java.util.HashSet<>()).add(e.logDate());
      }
    }

    List<AvailabilityPerformanceRow> rows = new ArrayList<>(agg.size());
    for (Map.Entry<UUID, double[]> e : agg.entrySet()) {
      MachineDisplay m = machinesByKey.get(e.getKey());
      ResourceEquipmentDetails d = detailsById.get(e.getKey());  // null for role-only
      double[] v = e.getValue();
      double total = v[0] + v[1] + v[2];
      double availability = total > 0 ? (v[0] + v[1]) / total : 0d;

      // Prefer the role-only EquipmentRoleVariant.standardOutputPerDay (carried on MachineDisplay);
      // fall back to legacy ResourceEquipmentDetails.standardOutputPerDay when the role-only path
      // didn't supply one.
      double standardPerDay = m != null && m.standardOutputPerDay() > 0
          ? m.standardOutputPerDay()
          : (d != null && d.getStandardOutputPerDay() != null
              ? d.getStandardOutputPerDay().doubleValue() : 0d);
      double daysCount = daysSeen.getOrDefault(e.getKey(), Set.of()).size();
      double actualPerDay = daysCount > 0 ? v[3] / daysCount : 0d;
      double performance = standardPerDay > 0 ? actualPerDay / standardPerDay : 0d;
      double outputRatePerHour = v[0] > 0d ? v[3] / v[0] : 0d;

      rows.add(new AvailabilityPerformanceRow(
          e.getKey(),
          m != null ? m.code() : "?",
          m != null ? m.name() : "Unknown",
          round4(availability),
          round4(performance),
          round4(outputRatePerHour)));
    }
    rows.sort(Comparator.comparing(AvailabilityPerformanceRow::resourceCode));
    return rows;
  }

  // ---------- Owned vs rented ----------

  private List<OwnedRentedSlice> computeOwnedVsRented(
      List<EnrichedDprEquipment> enriched,
      Map<UUID, Resource> resourcesById,
      Map<UUID, ResourceEquipmentDetails> detailsById) {
    Map<String, double[]> bucket = new HashMap<>(); // [hours, cost]
    for (EnrichedDprEquipment e : enriched) {
      DprEquipment l = e.row();
      double hours = l.getWorkingHours() != null ? l.getWorkingHours().doubleValue() : 0d;
      double cost;
      if (l.getLineCost() != null && l.getLineCost().signum() > 0) {
        cost = l.getLineCost().doubleValue();
      } else {
        Resource r = l.getResourceId() != null ? resourcesById.get(l.getResourceId()) : null;
        BigDecimal cpu = r != null ? r.getCostPerUnit() : null;
        cost = (cpu != null ? cpu.doubleValue() : 0d) * hours;
      }
      // Ownership resolution: prefer the DPR row's own enum (set by supervisor or seeded). Fall
      // back to ResourceEquipmentDetails.ownershipType for legacy rows that didn't capture it on
      // the DPR. Role-only rows that pre-date the supervisor capture-step bucket as UNKNOWN.
      String key;
      if (l.getOwnership() != null) {
        key = l.getOwnership().name();
      } else {
        ResourceEquipmentDetails d = l.getResourceId() != null ? detailsById.get(l.getResourceId()) : null;
        ResourceOwnership ownership = d != null ? d.getOwnershipType() : null;
        key = ownership != null ? ownership.name() : "UNKNOWN";
      }
      double[] acc = bucket.computeIfAbsent(key, k -> new double[2]);
      acc[0] += hours;
      acc[1] += cost;
    }
    List<OwnedRentedSlice> slices = new ArrayList<>(bucket.size());
    for (Map.Entry<String, double[]> e : bucket.entrySet()) {
      slices.add(new OwnedRentedSlice(e.getKey(), round2(e.getValue()[0]), round2(e.getValue()[1])));
    }
    slices.sort(Comparator.comparingDouble(OwnedRentedSlice::cost).reversed());
    return slices;
  }

  // ---------- Service-due ----------

  private List<ServiceDueRow> computeServiceDue(
      UUID projectId,
      Map<UUID, Resource> resourcesById,
      Map<UUID, ResourceEquipmentDetails> detailsById) {
    LocalDate today = LocalDate.now();
    LocalDate cutoff = today.plusDays(DEFAULT_SERVICE_LOOKAHEAD_DAYS);
    List<ServiceDueRow> rows = new ArrayList<>();
    for (Map.Entry<UUID, ResourceEquipmentDetails> e : detailsById.entrySet()) {
      ResourceEquipmentDetails d = e.getValue();
      if (d.getNextServiceDate() == null) continue;
      if (d.getNextServiceDate().isAfter(cutoff)) continue;
      Resource r = resourcesById.get(e.getKey());
      if (r == null || r.getResourceType() == null
          || !EQUIPMENT_TYPE_CODE.equalsIgnoreCase(r.getResourceType().getCode())) continue;
      long days = today.until(d.getNextServiceDate()).getDays();
      rows.add(new ServiceDueRow(
          e.getKey(),
          r.getCode(),
          r.getName(),
          d.getNextServiceDate(),
          days));
    }
    rows.sort(Comparator.comparingLong(ServiceDueRow::daysUntilService));
    log.debug("Equipment service-due rows for project {}: {}", projectId, rows.size());
    return rows;
  }

  // ---------- Empty response ----------

  private EquipmentKpiResponse emptyResponse(UUID projectId, LocalDate from, LocalDate to) {
    return new EquipmentKpiResponse(
        projectId, from, to,
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        0d, 0d, 0d,
        0, 0, 0d);
  }

  // ---------- Misc ----------

  private static double round2(double v) {
    return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  private static double round3(double v) {
    return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
  }

  private static double round4(double v) {
    return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
  }
}
