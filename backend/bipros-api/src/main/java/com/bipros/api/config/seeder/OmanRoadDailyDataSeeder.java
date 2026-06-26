package com.bipros.api.config.seeder;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.DailyResourceDeployment;
import com.bipros.project.domain.model.DailyWeather;
import com.bipros.project.domain.model.DeploymentResourceType;
import com.bipros.project.domain.model.NextDayPlan;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DailyResourceDeploymentRepository;
import com.bipros.project.domain.repository.DailyWeatherRepository;
import com.bipros.project.domain.repository.NextDayPlanRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.resource.domain.model.EquipmentLog;
import com.bipros.resource.domain.model.EquipmentStatus;
import com.bipros.resource.domain.model.GoodsReceiptNote;
import com.bipros.resource.domain.model.LabTestStatus;
import com.bipros.resource.domain.model.LabourDesignation;
import com.bipros.resource.domain.model.LabourReturn;
import com.bipros.resource.domain.model.Material;
import com.bipros.resource.domain.model.MaterialCategory;
import com.bipros.resource.domain.model.MaterialConsumptionLog;
import com.bipros.resource.domain.model.MaterialReconciliation;
import com.bipros.resource.domain.model.MaterialSource;
import com.bipros.resource.domain.model.MaterialSourceType;
import com.bipros.resource.domain.model.MaterialStatus;
import com.bipros.resource.domain.model.MaterialStock;
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.SkillCategory;
import com.bipros.resource.domain.model.StockStatusTag;
import com.bipros.resource.domain.repository.EquipmentLogRepository;
import com.bipros.resource.domain.repository.GoodsReceiptNoteRepository;
import com.bipros.resource.domain.repository.LabourDesignationRepository;
import com.bipros.resource.domain.repository.LabourReturnRepository;
import com.bipros.resource.domain.repository.MaterialConsumptionLogRepository;
import com.bipros.resource.domain.repository.MaterialReconciliationRepository;
import com.bipros.resource.domain.repository.MaterialRepository;
import com.bipros.resource.domain.repository.MaterialSourceRepository;
import com.bipros.resource.domain.repository.MaterialStockRepository;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Oman Barka–Nakhal Road Project — daily-operations seeder. Runs at
 * {@code @Order(143)}, after {@code OmanRoadProjectSeeder} (Agent 2,
 * {@code @Order(141)}) has produced the project, WBS, BOQ, activities,
 * resources, and labour deployments. Mirrors {@link NhaiRoadProjectSeeder}'s
 * daily-data methods but uses synthetic, deterministic-RNG values seeded with
 * the project code (6155) so re-runs produce identical output.
 *
 * <p>Date window: {@code dataDate − 90} through {@code dataDate − 1}. Working
 * days are Sun–Thu (Oman work week). All money/rates are OMR.
 *
 * <p>Sentinel: skip if any DPRs already exist for the project (idempotent
 * re-runs).
 *
 * <p>Tables seeded:
 * <ul>
 *   <li>~64 Daily Progress Reports (one per Sun–Thu, supervisor cycled across
 *       the 4 named site supervisors)</li>
 *   <li>~768 Daily Resource Deployment rows (12 / day)</li>
 *   <li>90 Daily Weather rows (every calendar day)</li>
 *   <li>~64 Next-Day Plans (one per DPR day)</li>
 *   <li>~108 Material Consumption Logs (12 active materials × 9 sample days)</li>
 *   <li>6 MaterialSource rows (Galfar Aggregates, Oman Cement, etc.)</li>
 *   <li>23 MaterialStock rows (one per BNK-MT-* resource)</li>
 *   <li>~30 GRNs across the 6 sources</li>
 *   <li>4 monthly MaterialReconciliations (Feb/Mar/Apr/Summary 2026)</li>
 *   <li>Equipment Logs — weekly per equipment for 13 weeks (~440)</li>
 *   <li>~640 Labour Returns (10 designations × 64 working days)</li>
 * </ul>
 *
 * <p>NOTE: deliberately no {@code @Transactional} on {@link #run(String...)}
 * (mirrors {@code NhaiRoadProjectSeeder}). Each save commits row-by-row so
 * Agent 4's SQL bundle can resolve the seeded IDs via natural-key subqueries.
 */
@Slf4j
@Component
@Profile("seed")
@Order(143)
@RequiredArgsConstructor
public class OmanRoadDailyDataSeeder implements CommandLineRunner {

  private static final String PROJECT_CODE = "6155";
  private static final long PROJECT_RNG_SEED = 6155L;
  /** Oman corridor stretches 41 km (Barka → Nakhal). Working "front" advances ~450 m/day. */
  private static final long CHAINAGE_START_M = 0L;
  private static final long CHAINAGE_END_M = 41_000L;
  private static final long FRONT_ADVANCE_M_PER_DAY = 450L;

  private static final String[] SUPERVISORS = {"T. Swamy", "Nagarajan", "A.K. Singh", "Anbazhagan"};

  /**
   * Operability: leave the LAST 3 working days un-seeded for deployment so the demo user has
   * blank dates to enter fresh deployment data and immediately see it surface in dashboards.
   */
  private static final int DEPLOYMENT_BLANK_TAIL_DAYS = 3;

  /**
   * Operability: among the most recent 12 working days, leave 4 un-seeded for material
   * consumption — same rationale as the deployment tail, but tuned for the consumption cadence.
   */
  private static final int CONSUMPTION_BLANK_TAIL_DAYS = 4;

  /** Phrase bank for {@link NextDayPlan#getConcerns()}. Index = day index % length. */
  private static final String[] CONCERN_BANK = {
      "Bitumen delivery delay possible",
      "Survey crew on standby",
      "Concrete pour scheduled 14:00",
      "Heat stress mitigation in effect",
      "OETC HT line clearance pending",
      "Safety toolbox briefing at 06:30",
      "Traffic diversion approved by ROP",
      "Asphalt plant calibration required"
  };

  /** Materials actively consumed on the project — 12 of the 23 catalogue items. */
  private static final String[] ACTIVE_MATERIALS = {
      "Cement OPC 43",
      "Aggregate 20mm",
      "Aggregate 10mm",
      "Sand Washed",
      "GSB",
      "WMM",
      "Bitumen VG-30",
      "DBM Mix",
      "BC Mix",
      "Steel Fe500",
      "Concrete C30",
      "Royalty Charges"
  };

  /** Full 23-material catalogue per plan §4.7. Resource codes are BNK-MT-*. */
  private static final List<MaterialSpec> MATERIAL_CATALOG = List.of(
      new MaterialSpec("BNK-MT-CEMENT-OPC43",   "Cement OPC 43",          MaterialCategory.CEMENT,    "MT",    "OPC 43"),
      new MaterialSpec("BNK-MT-CEMENT-PPC",     "Cement PPC",             MaterialCategory.CEMENT,    "MT",    "PPC"),
      new MaterialSpec("BNK-MT-AGG-20MM",       "Aggregate 20mm",         MaterialCategory.AGGREGATE, "CU_M",  "20mm"),
      new MaterialSpec("BNK-MT-AGG-10MM",       "Aggregate 10mm",         MaterialCategory.AGGREGATE, "CU_M",  "10mm"),
      new MaterialSpec("BNK-MT-AGG-6MM",        "Aggregate 6mm",          MaterialCategory.AGGREGATE, "CU_M",  "6mm"),
      new MaterialSpec("BNK-MT-AGG-DUST",       "Stone Dust",             MaterialCategory.AGGREGATE, "CU_M",  "Dust"),
      new MaterialSpec("BNK-MT-SAND-WASHED",    "Sand Washed",            MaterialCategory.SAND,      "CU_M",  "Washed"),
      new MaterialSpec("BNK-MT-SAND-CRUSHED",   "Sand Crushed",           MaterialCategory.SAND,      "CU_M",  "Crushed"),
      new MaterialSpec("BNK-MT-GSB",            "GSB",                    MaterialCategory.GRANULAR,  "CU_M",  "GSB Grade-II"),
      new MaterialSpec("BNK-MT-WMM",            "WMM",                    MaterialCategory.GRANULAR,  "CU_M",  "WMM Grade-III"),
      new MaterialSpec("BNK-MT-BITUMEN-VG30",   "Bitumen VG-30",          MaterialCategory.BITUMINOUS,"MT",    "VG-30"),
      new MaterialSpec("BNK-MT-EMULSION",       "Bitumen Emulsion",       MaterialCategory.BITUMINOUS,"MT",    "RS-1"),
      new MaterialSpec("BNK-MT-DBM-MIX",        "DBM Mix",                MaterialCategory.BITUMINOUS,"MT",    "DBM Grade-II"),
      new MaterialSpec("BNK-MT-BC-MIX",         "BC Mix",                 MaterialCategory.BITUMINOUS,"MT",    "BC Grade-II"),
      new MaterialSpec("BNK-MT-STEEL-FE500",    "Steel Fe500",            MaterialCategory.STEEL,     "MT",    "Fe500D"),
      new MaterialSpec("BNK-MT-STEEL-BIND",     "Binding Wire",           MaterialCategory.STEEL,     "KG",    "16 SWG"),
      new MaterialSpec("BNK-MT-CONCRETE-C30",   "Concrete C30",           MaterialCategory.PRECAST,   "CU_M",  "C30/37"),
      new MaterialSpec("BNK-MT-CONCRETE-C40",   "Concrete C40",           MaterialCategory.PRECAST,   "CU_M",  "C40/50"),
      new MaterialSpec("BNK-MT-PIPE-RCC",       "RCC Pipe NP3",           MaterialCategory.PRECAST,   "RMT",   "NP3 1200mm"),
      new MaterialSpec("BNK-MT-MARKING-PAINT",  "Road Marking Paint",     MaterialCategory.ROAD_MARKING, "LITRE", "Thermoplastic"),
      new MaterialSpec("BNK-MT-MARKING-BEAD",   "Glass Beads",            MaterialCategory.ROAD_MARKING, "KG",  "Reflective"),
      new MaterialSpec("BNK-MT-DIESEL",         "HSD Diesel",             MaterialCategory.AGGREGATE, "LITRE", "EuroIV"),
      new MaterialSpec("BNK-MT-ROYALTY",        "Royalty Charges",        MaterialCategory.GRANULAR,  "MT",    "MEM Royalty")
  );

  /** Plan §4.21 supplier catalogue, mapped to the available {@link MaterialSourceType} values. */
  private static final List<SupplierSpec> SUPPLIER_CATALOG = List.of(
      new SupplierSpec("BNK-SRC-GALFAR",     "Galfar Aggregates",          MaterialSourceType.QUARRY,        "Sohar",          3, new BigDecimal("5.50")),
      new SupplierSpec("BNK-SRC-OMAN-CEMENT","Oman Cement Co.",            MaterialSourceType.CEMENT_SOURCE, "Rusayl",         5, new BigDecimal("8.00")),
      new SupplierSpec("BNK-SRC-SHELL-BIT",  "Shell Bitumen Oman",         MaterialSourceType.BITUMEN_DEPOT, "Mina Al Fahal",  7, new BigDecimal("12.00")),
      new SupplierSpec("BNK-SRC-ALHASSAN",   "Al-Hassan Engineering Steel",MaterialSourceType.BORROW_AREA,   "Muscat",         4, new BigDecimal("7.50")),
      new SupplierSpec("BNK-SRC-BAHWAN",     "Bahwan Pipes",               MaterialSourceType.BORROW_AREA,   "Sohar",          6, new BigDecimal("6.00")),
      new SupplierSpec("BNK-SRC-WADI-MISTAL","Local Quarry – Wadi Mistal", MaterialSourceType.QUARRY,        "Wadi Mistal",    1, new BigDecimal("2.50"))
  );

  // ───────────────────────────── Repositories ─────────────────────────────

  private final ProjectRepository projectRepository;
  private final ActivityRepository activityRepository;
  private final ResourceRepository resourceRepository;
  private final ProductivityNormRepository productivityNormRepository;

  private final DailyProgressReportRepository dprRepository;
  private final DailyResourceDeploymentRepository deploymentRepository;
  private final DailyWeatherRepository weatherRepository;
  private final NextDayPlanRepository nextDayPlanRepository;
  private final MaterialConsumptionLogRepository materialConsumptionLogRepository;

  private final MaterialRepository materialRepository;
  private final MaterialSourceRepository materialSourceRepository;
  private final MaterialStockRepository materialStockRepository;
  private final GoodsReceiptNoteRepository grnRepository;
  private final MaterialReconciliationRepository materialReconciliationRepository;

  private final EquipmentLogRepository equipmentLogRepository;
  private final LabourReturnRepository labourReturnRepository;
  private final LabourDesignationRepository labourDesignationRepository;

  // ───────────────────────────── Entry point ─────────────────────────────

  @Override
  public void run(String... args) {
    Optional<Project> projectOpt = projectRepository.findByCode(PROJECT_CODE);
    if (projectOpt.isEmpty()) {
      log.warn("[BNK-DAILY] project '{}' not found — Agent 2's seeder hasn't run; skipping",
          PROJECT_CODE);
      return;
    }
    Project project = projectOpt.get();
    UUID projectId = project.getId();

    // Idempotency gate: skip the entire seeder when ANY daily-ops data already exists.
    // Checking only DPRs is too narrow — the test-data reset endpoint wipes DPRs but leaves
    // environmental rows like weather alone. Without this broader check, a re-boot after a
    // reset triggers duplicate-key violations on weather (and other once-only tables) and
    // worse, re-creates the DPRs/deployments the user just wiped on purpose.
    boolean dprsExist = !dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId).isEmpty();
    boolean weatherExists = !weatherRepository.findByProjectIdOrderByLogDateAscIdAsc(projectId).isEmpty();
    if (dprsExist || weatherExists) {
      log.info("[BNK-DAILY] daily-ops data already present for project '{}' (dprs={}, weather={}) — skipping",
          PROJECT_CODE, dprsExist, weatherExists);
      return;
    }

    // Resolve dataDate window: dataDate-90 .. dataDate-1.
    LocalDate dataDate = project.getDataDate() != null ? project.getDataDate() : LocalDate.of(2026, 4, 29);
    LocalDate from = dataDate.minusDays(90);
    LocalDate to = dataDate.minusDays(1);

    log.info("[BNK-DAILY] seeding daily-ops for project '{}' window {} → {}",
        PROJECT_CODE, from, to);

    Random rng = new Random(PROJECT_RNG_SEED);

    // Collections needed by multiple methods — fetched once.
    List<Activity> activities = activityRepository.findByProjectId(projectId);
    List<ProductivityNorm> norms = productivityNormRepository.findAll();
    List<Resource> equipment = resourceRepository.findByResourceType_Code("EQUIPMENT");
    List<Resource> materials = resourceRepository.findByResourceType_Code("MATERIAL").stream()
        .filter(r -> r.getCode() != null && r.getCode().startsWith("BNK-MT"))
        .toList();
    List<Resource> labourPool = resourceRepository.findByResourceType_Code("LABOR");
    List<LabourDesignation> designations = labourDesignationRepository.findAll();

    // Compute working days (Sun–Thu) in window.
    List<LocalDate> workingDays = workingDaysBetween(from, to);
    List<LocalDate> calendarDays = calendarDaysBetween(from, to);

    int dprCount = seedDailyProgressReports(projectId, project, workingDays, activities, norms, rng);
    int deployCount = seedResourceDeployments(projectId, workingDays, equipment, labourPool, rng);
    int weatherCount = seedDailyWeather(projectId, calendarDays);
    int planCount = seedNextDayPlans(projectId, workingDays, activities, norms, rng);

    // Materials: catalogue rows first (needed by stock / GRN FKs), then daily ops.
    Map<String, UUID> materialIdByCode = ensureMaterialCatalog(projectId);
    int sourceCount = seedMaterialSources(projectId);
    int stockCount = seedMaterialStock(projectId, materialIdByCode, rng);
    int grnCount = seedGrns(projectId, materialIdByCode, from, to, rng);
    int reconCount = seedMaterialReconciliations(projectId, materialIdByCode, materials);
    int mclCount = seedMaterialConsumptionLogs(projectId, workingDays, materials, rng);

    int eqLogCount = seedEquipmentLogs(projectId, equipment, from, to, labourPool, rng);
    int labourReturnCount = seedLabourReturns(projectId, designations, workingDays, rng);

    log.info("[BNK-DAILY] Seeded {} DPRs", dprCount);
    log.info("[BNK-DAILY] Seeded {} deployment rows", deployCount);
    log.info("[BNK-DAILY] Seeded {} weather rows", weatherCount);
    log.info("[BNK-DAILY] Seeded {} next-day plans", planCount);
    log.info("[BNK-DAILY] Seeded {} material consumption logs", mclCount);
    log.info("[BNK-DAILY] Seeded {} material sources, {} stock rows, {} GRNs, {} reconciliations",
        sourceCount, stockCount, grnCount, reconCount);
    log.info("[BNK-DAILY] Seeded {} equipment logs, {} labour returns",
        eqLogCount, labourReturnCount);
  }

  // ─────────────────────── 1. Daily Progress Reports ──────────────────────

  private int seedDailyProgressReports(UUID projectId, Project project,
                                       List<LocalDate> workingDays, List<Activity> activities,
                                       List<ProductivityNorm> norms, Random rng) {
    if (workingDays.isEmpty()) return 0;
    Map<String, BigDecimal> cumulativeByActivity = new HashMap<>();
    long workFront = CHAINAGE_START_M;
    List<DailyProgressReport> rows = new ArrayList<>();

    for (int i = 0; i < workingDays.size(); i++) {
      LocalDate day = workingDays.get(i);
      String supervisor = SUPERVISORS[i % SUPERVISORS.length];
      Activity act = pickActiveActivity(activities, day);
      String activityName = act != null
          ? truncate(act.getName(), 140)
          : "General Earthwork";

      // Daily working front — ~450 m/day along the corridor.
      long fromM = Math.min(workFront, CHAINAGE_END_M);
      long toM = Math.min(fromM + FRONT_ADVANCE_M_PER_DAY, CHAINAGE_END_M);
      workFront = toM;

      BigDecimal qty = pickDailyOutput(act, norms, rng);
      BigDecimal cumulative = cumulativeByActivity.getOrDefault(activityName, BigDecimal.ZERO).add(qty);
      cumulativeByActivity.put(activityName, cumulative);

      String unit = pickUnit(act, norms);
      DailyProgressReport d = DailyProgressReport.builder()
          .projectId(projectId)
          .reportDate(day)
          .supervisorName(supervisor)
          .chainageFromM(fromM)
          .chainageToM(toM)
          .activityName(activityName)
          .unit(unit)
          .qtyExecuted(qty)
          // cumulativeQty is computed on read — no longer stored.
          .weatherCondition(weatherConditionForDay(day, i))
          .remarks("Section " + supervisor)
          .approvalStatus(DprApprovalStatus.APPROVED)
          .build();
      rows.add(d);
    }
    dprRepository.saveAll(rows);
    return rows.size();
  }

  private Activity pickActiveActivity(List<Activity> activities, LocalDate day) {
    if (activities == null || activities.isEmpty()) return null;
    List<Activity> active = new ArrayList<>();
    for (Activity a : activities) {
      LocalDate s = a.getPlannedStartDate();
      LocalDate f = a.getPlannedFinishDate();
      if (s != null && f != null && !day.isBefore(s) && !day.isAfter(f)) {
        active.add(a);
      }
    }
    if (active.isEmpty()) return activities.get(0);
    // Deterministic: pick by day-of-year modulo
    return active.get(day.getDayOfYear() % active.size());
  }

  private BigDecimal pickDailyOutput(Activity act, List<ProductivityNorm> norms, Random rng) {
    BigDecimal base = new BigDecimal("100");
    if (act != null && norms != null && !norms.isEmpty()) {
      String needle = act.getName() == null ? "" : act.getName().toLowerCase(Locale.ROOT);
      for (ProductivityNorm n : norms) {
        String name = n.getActivityName();
        if (name == null) continue;
        String key = name.toLowerCase(Locale.ROOT).split("[\\-(,]", 2)[0].trim();
        if (!key.isEmpty() && needle.contains(key) && n.getOutputPerDay() != null
            && n.getOutputPerDay().signum() > 0) {
          base = n.getOutputPerDay();
          break;
        }
      }
    }
    // Multiplier in [0.85, 1.10]
    double mult = 0.85 + rng.nextDouble() * 0.25;
    return base.multiply(BigDecimal.valueOf(mult)).setScale(3, RoundingMode.HALF_UP);
  }

  private String pickUnit(Activity act, List<ProductivityNorm> norms) {
    if (act != null && norms != null) {
      String needle = act.getName() == null ? "" : act.getName().toLowerCase(Locale.ROOT);
      for (ProductivityNorm n : norms) {
        String name = n.getActivityName();
        if (name == null) continue;
        if (needle.contains(name.toLowerCase(Locale.ROOT).split("[\\-(,]", 2)[0].trim())
            && n.getUnit() != null) {
          return n.getUnit();
        }
      }
    }
    return "Cum";
  }

  private String weatherConditionForDay(LocalDate day, int idx) {
    String[] cycle = {"CLEAR", "CLOUDY", "RAINY", "HEATWAVE"};
    return cycle[idx % cycle.length];
  }

  // ─────────────────── 2. Daily Resource Deployment ───────────────────

  private int seedResourceDeployments(UUID projectId, List<LocalDate> workingDays,
                                      List<Resource> equipment, List<Resource> labourPool,
                                      Random rng) {
    int rowsPerDay = 12;
    int equipmentSlots = 8;
    int labourSlots = 4;
    int total = 0;

    String[] equipmentDescriptions = {
        "Excavator (CAT 320)",
        "Bull Dozer",
        "Wheel Loader",
        "Tipper",
        "Vibratory Roller",
        "Grader",
        "Asphalt Paver",
        "Tandem Roller",
        "Tipper Dump Truck",
        "Water Tanker",
        "JCB Backhoe",
        "Concrete Mixer Truck"
    };
    String[] labourDescriptions = {
        "Mason Crew",
        "Steel Fixer Crew",
        "Asphalt Crew",
        "Carpenter Crew",
        "Helper Crew",
        "Foreman + Helper Mix"
    };

    // Operability: leave the last DEPLOYMENT_BLANK_TAIL_DAYS un-seeded so the demo user has
    // empty dates to enter fresh deployment data and immediately see it surface.
    int seedDayCount = Math.max(0, workingDays.size() - DEPLOYMENT_BLANK_TAIL_DAYS);
    List<LocalDate> deploymentDays = workingDays.subList(0, seedDayCount);

    List<DailyResourceDeployment> rows = new ArrayList<>();
    for (LocalDate day : deploymentDays) {
      // 8 equipment rows
      for (int e = 0; e < equipmentSlots; e++) {
        int planned = 2 + rng.nextInt(4); // 2..5
        int deployed = Math.max(0, planned - rng.nextInt(3)); // planned − 0..2
        double hoursWorked = round2(deployed * 8.0 * (0.85 + rng.nextDouble() * 0.15));
        double idleHours = round2(planned * 8.0 - hoursWorked);
        if (idleHours < 0) idleHours = 0.0;

        UUID resourceId = !equipment.isEmpty()
            ? equipment.get((day.getDayOfYear() + e) % equipment.size()).getId()
            : null;
        String desc = equipmentDescriptions[e % equipmentDescriptions.length];

        rows.add(DailyResourceDeployment.builder()
            .projectId(projectId)
            .logDate(day)
            .resourceType(DeploymentResourceType.EQUIPMENT)
            .resourceDescription(desc)
            .resourceId(resourceId)
            .nosPlanned(planned)
            .nosDeployed(deployed)
            .hoursWorked(hoursWorked)
            .idleHours(idleHours)
            .remarks(null)
            .build());
        total++;
      }
      // 4 labour rows
      for (int l = 0; l < labourSlots; l++) {
        int planned = 4 + rng.nextInt(8); // 4..11
        int deployed = Math.max(0, planned - rng.nextInt(3));
        double hoursWorked = round2(deployed * 8.0 * (0.85 + rng.nextDouble() * 0.15));
        double idleHours = round2(planned * 8.0 - hoursWorked);
        if (idleHours < 0) idleHours = 0.0;

        UUID resourceId = !labourPool.isEmpty()
            ? labourPool.get((day.getDayOfYear() + l) % labourPool.size()).getId()
            : null;
        String desc = labourDescriptions[l % labourDescriptions.length];

        rows.add(DailyResourceDeployment.builder()
            .projectId(projectId)
            .logDate(day)
            .resourceType(DeploymentResourceType.MANPOWER)
            .resourceDescription(desc)
            .resourceId(resourceId)
            .nosPlanned(planned)
            .nosDeployed(deployed)
            .hoursWorked(hoursWorked)
            .idleHours(idleHours)
            .remarks(null)
            .build());
        total++;
      }
      // Flush in chunks to avoid huge in-memory list.
      if (rows.size() >= 200) {
        deploymentRepository.saveAll(rows);
        rows.clear();
      }
    }
    if (!rows.isEmpty()) deploymentRepository.saveAll(rows);
    return total;
  }

  // ─────────────────────── 3. Daily Weather ──────────────────────────

  private int seedDailyWeather(UUID projectId, List<LocalDate> calendarDays) {
    // Plan §4.18 Oman climate norms by month: Jan 14/24, Feb 15/26, Mar 18/30, Apr 22/35.
    String[] cycle = {"CLEAR", "CLOUDY", "RAINY", "HEATWAVE"};
    List<DailyWeather> rows = new ArrayList<>();
    int idx = 0;
    for (LocalDate day : calendarDays) {
      double[] minMax = climateNormsFor(day.getMonth());
      double tempMin = minMax[0];
      double tempMax = minMax[1];
      // Slight ±1 °C wobble keyed off day-of-year so re-runs are stable.
      double wobble = ((day.getDayOfYear() % 5) - 2) * 0.5;
      tempMin = round1(tempMin + wobble);
      tempMax = round1(tempMax + wobble);

      String condition = cycle[idx % cycle.length];
      // Rainfall: only for RAINY days (≥ 2 mm), with one heavy event each cycle.
      double rainfallMm;
      if ("RAINY".equals(condition)) {
        rainfallMm = (idx % 8 == 0) ? 12.5 : 4.0;
      } else {
        rainfallMm = 0.0;
      }
      double windKmh = 8.0 + (idx % 7) * 1.5;
      // Plan §4.18: HEATWAVE drops working hours to 6; heavy rain stops work entirely.
      Double workingHours;
      if ("RAINY".equals(condition) && rainfallMm > 8.0) {
        workingHours = 0.0;
      } else if ("HEATWAVE".equals(condition)) {
        // Heat-wave: peak summer temperatures bump max by +5 °C and curtail outdoor hours.
        tempMax = round1(tempMax + 5.0);
        workingHours = 6.0;
      } else {
        workingHours = 8.0;
      }

      String remarks = rainfallMm > 8.0
          ? "Working day lost — rainfall " + rainfallMm + " mm"
          : null;

      rows.add(DailyWeather.builder()
          .projectId(projectId)
          .logDate(day)
          .tempMaxC(tempMax)
          .tempMinC(tempMin)
          .rainfallMm(rainfallMm)
          .windKmh(round1(windKmh))
          .weatherCondition(condition)
          .workingHours(workingHours)
          .remarks(remarks)
          .build());
      idx++;
    }
    weatherRepository.saveAll(rows);
    return rows.size();
  }

  /** Plan §4.18 Oman climate (min/max °C) by month. */
  private double[] climateNormsFor(Month month) {
    return switch (month) {
      case JANUARY -> new double[]{14.0, 24.0};
      case FEBRUARY -> new double[]{15.0, 26.0};
      case MARCH -> new double[]{18.0, 30.0};
      case APRIL -> new double[]{22.0, 35.0};
      case MAY -> new double[]{26.0, 38.0};
      // Fallback: assume late spring conditions for any other month seen.
      default -> new double[]{20.0, 32.0};
    };
  }

  // ─────────────────────── 4. Next-Day Plans ────────────────────────

  private int seedNextDayPlans(UUID projectId, List<LocalDate> workingDays,
                               List<Activity> activities, List<ProductivityNorm> norms,
                               Random rng) {
    if (workingDays.isEmpty()) return 0;
    long workFront = CHAINAGE_START_M;
    List<NextDayPlan> rows = new ArrayList<>();

    for (int i = 0; i < workingDays.size(); i++) {
      LocalDate day = workingDays.get(i);
      LocalDate due = day.plusDays(1);
      Activity act = pickActiveActivity(activities, day);
      String activityName = act != null
          ? truncate(act.getName(), 180)
          : "General Earthwork";

      long fromM = Math.min(workFront + FRONT_ADVANCE_M_PER_DAY, CHAINAGE_END_M);
      long toM = Math.min(fromM + FRONT_ADVANCE_M_PER_DAY, CHAINAGE_END_M);
      workFront = fromM;

      // Target qty: norm × deployedCrew (use 1..3 crews deterministically).
      BigDecimal targetQty = pickDailyOutput(act, norms, rng);
      int crews = 1 + (i % 3);
      targetQty = targetQty.multiply(BigDecimal.valueOf(crews)).setScale(2, RoundingMode.HALF_UP);

      String unit = pickUnit(act, norms);
      String concerns = CONCERN_BANK[i % CONCERN_BANK.length];
      String actionBy = SUPERVISORS[i % SUPERVISORS.length];

      rows.add(NextDayPlan.builder()
          .projectId(projectId)
          .reportDate(day)
          .nextDayActivity(activityName)
          .chainageFromM(fromM)
          .chainageToM(toM)
          .targetQty(targetQty)
          .unit(unit)
          .concerns(concerns)
          .actionBy(actionBy)
          .dueDate(due)
          .build());
    }
    nextDayPlanRepository.saveAll(rows);
    return rows.size();
  }

  // ─────────────────── 5. Material Consumption Logs ─────────────────

  private int seedMaterialConsumptionLogs(UUID projectId, List<LocalDate> workingDays,
                                          List<Resource> materials, Random rng) {
    if (workingDays.isEmpty()) return 0;
    // Operability: skip consumption rows on the most recent CONSUMPTION_BLANK_TAIL_DAYS so the
    // user has empty dates to enter fresh consumption logs.
    int eligibleCount = Math.max(0, workingDays.size() - CONSUMPTION_BLANK_TAIL_DAYS);
    List<LocalDate> eligibleDays = workingDays.subList(0, eligibleCount);

    // 9 sample days: every nth eligible working day.
    int sampleCount = 9;
    int step = Math.max(1, eligibleDays.size() / sampleCount);
    List<LocalDate> sampleDays = new ArrayList<>();
    for (int i = 0; i < eligibleDays.size() && sampleDays.size() < sampleCount; i += step) {
      sampleDays.add(eligibleDays.get(i));
    }

    Map<String, BigDecimal> openingByMaterial = new HashMap<>();
    List<MaterialConsumptionLog> rows = new ArrayList<>();
    for (String matName : ACTIVE_MATERIALS) {
      // Deterministic per-material opening + per-day flows. Stable across reruns so
      // material consumption + cost rollups reproduce exactly.
      int matHash = Math.abs(matName.hashCode());
      BigDecimal opening = BigDecimal.valueOf(500L + (matHash % 2001L));
      openingByMaterial.put(matName, opening);
      String unit = resolveMaterialUnit(matName);
      UUID resourceId = findResourceIdByMaterialName(materials, matName);
      int dayIdx = 0;
      for (LocalDate day : sampleDays) {
        int dayHash = matHash + dayIdx * 31;
        BigDecimal received = BigDecimal.valueOf(50L + (dayHash % 151L));
        BigDecimal consumed = BigDecimal.valueOf(40L + ((dayHash / 7) % 121L));
        BigDecimal closing = opening.add(received).subtract(consumed);
        if (closing.signum() < 0) {
          // Top up to keep balance non-negative.
          BigDecimal topUp = closing.abs().add(BigDecimal.valueOf(10));
          received = received.add(topUp);
          closing = opening.add(received).subtract(consumed);
        }
        BigDecimal wastage = BigDecimal.valueOf(0.5 + ((dayHash % 351) / 100.0))
            .setScale(2, RoundingMode.HALF_UP);

        rows.add(MaterialConsumptionLog.builder()
            .projectId(projectId)
            .logDate(day)
            .resourceId(resourceId)
            .materialName(matName)
            .unit(unit)
            .openingStock(opening.setScale(3, RoundingMode.HALF_UP))
            .received(received.setScale(3, RoundingMode.HALF_UP))
            .consumed(consumed.setScale(3, RoundingMode.HALF_UP))
            .closingStock(closing.setScale(3, RoundingMode.HALF_UP))
            .wastagePercent(wastage)
            .issuedBy(SUPERVISORS[dayIdx % SUPERVISORS.length])
            .receivedBy(SUPERVISORS[(dayIdx + 1) % SUPERVISORS.length])
            .remarks(null)
            .build());
        opening = closing;
        openingByMaterial.put(matName, closing);
        dayIdx++;
      }
    }
    materialConsumptionLogRepository.saveAll(rows);
    return rows.size();
  }

  private String resolveMaterialUnit(String matName) {
    for (MaterialSpec spec : MATERIAL_CATALOG) {
      if (spec.name().equalsIgnoreCase(matName)) return spec.unit();
    }
    return "MT";
  }

  private UUID findResourceIdByMaterialName(List<Resource> materials, String materialName) {
    if (materialName == null || materials == null) return null;
    String needle = materialName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    for (Resource r : materials) {
      String rn = r.getName() == null ? "" : r.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
      if (!rn.isEmpty() && (rn.contains(needle) || needle.contains(rn))) return r.getId();
    }
    return null;
  }

  // ─────────────────────── 6. Material Sources ──────────────────────

  private int seedMaterialSources(UUID projectId) {
    if (!materialSourceRepository.findByProjectId(projectId).isEmpty()) {
      log.info("[BNK-DAILY] material sources already seeded for project — skipping");
      return materialSourceRepository.findByProjectId(projectId).size();
    }
    int saved = 0;
    for (SupplierSpec sup : SUPPLIER_CATALOG) {
      MaterialSource s = MaterialSource.builder()
          .projectId(projectId)
          .sourceCode(sup.code())
          .name(sup.name())
          .sourceType(sup.type())
          .village(sup.region())
          .district(sup.region())
          .state("Sultanate of Oman")
          .distanceKm(sup.transportCost().multiply(BigDecimal.valueOf(8))
              .setScale(2, RoundingMode.HALF_UP))
          .approvedQuantity(BigDecimal.valueOf(50_000))
          .approvedQuantityUnit("MT")
          .approvalReference("MEM/OMAN/" + sup.code() + "/2026")
          .approvalAuthority("Ministry of Energy & Minerals (MEM)")
          .labTestStatus(LabTestStatus.ALL_PASS)
          .build();
      materialSourceRepository.save(s);
      saved++;
    }
    return saved;
  }

  // ─────────────────────── Material catalogue ──────────────────────

  /**
   * Ensure a {@link Material} row exists per BNK-MT-* code so {@link MaterialStock}
   * and {@link GoodsReceiptNote} have a valid FK target. Idempotent — skips
   * codes already in the project.
   */
  private Map<String, UUID> ensureMaterialCatalog(UUID projectId) {
    Map<String, UUID> byCode = new HashMap<>();
    int created = 0;
    for (MaterialSpec spec : MATERIAL_CATALOG) {
      if (materialRepository.existsByProjectIdAndCode(projectId, spec.code())) {
        // Look it up.
        materialRepository.findByProjectId(projectId).stream()
            .filter(m -> spec.code().equals(m.getCode()))
            .findFirst()
            .ifPresent(m -> byCode.put(spec.code(), m.getId()));
        continue;
      }
      Material m = Material.builder()
          .projectId(projectId)
          .code(spec.code())
          .name(spec.name())
          .category(spec.category())
          .unit(spec.unit())
          .specificationGrade(spec.grade())
          .minStockLevel(BigDecimal.valueOf(50))
          .reorderQuantity(BigDecimal.valueOf(200))
          .leadTimeDays(7)
          .storageLocation("Site Stores")
          .status(MaterialStatus.ACTIVE)
          .build();
      Material saved = materialRepository.save(m);
      byCode.put(spec.code(), saved.getId());
      created++;
    }
    log.info("[BNK-DAILY] ensured {} Material catalogue rows ({} new)",
        MATERIAL_CATALOG.size(), created);
    return byCode;
  }

  // ─────────────────────── 7. Material Stock ────────────────────────

  private int seedMaterialStock(UUID projectId, Map<String, UUID> materialIdByCode, Random rng) {
    String[] binBays = {
        "Bay-A1", "Bay-A2", "Bay-A3", "Bay-A4", "Bay-A5", "Bay-A6",
        "Bay-B1", "Bay-B2", "Bay-B3", "Bay-B4", "Bay-B5", "Bay-B6",
        "Bay-C1", "Bay-C2", "Bay-C3", "Bay-C4", "Bay-C5", "Bay-C6",
        "Bay-D1", "Bay-D2", "Bay-D3", "Bay-D4", "Bay-D5", "Bay-D6"
    };
    int created = 0;
    int idx = 0;
    for (MaterialSpec spec : MATERIAL_CATALOG) {
      UUID materialId = materialIdByCode.get(spec.code());
      if (materialId == null) {
        idx++;
        continue;
      }
      if (materialStockRepository.findByProjectIdAndMaterialId(projectId, materialId).isPresent()) {
        idx++;
        continue;
      }
      // Deterministic stock + valuation per material — no RNG. Stock + rate are stable
      // functions of materialId hash so cost rollups reproduce on every reseed.
      int matHash = Math.abs(materialId.hashCode());
      double inStock = 50 + (matHash % 501);
      double opening = round2(inStock * 0.85);
      double receivedMonth = round2(inStock * 0.30);
      double issuedMonth = round2(inStock * 0.20);
      double current = round2(inStock);
      double cumulativeConsumed = round2(inStock * 1.5);
      double wastagePct = round2(0.5 + ((matHash % 351) / 100.0)); // 0.5..3.5
      double rate = 10.0 + (matHash % 80); // 10..90 OMR/unit, fixed per material
      double stockValue = round2(current * rate);

      StockStatusTag tag = current >= 100
          ? StockStatusTag.OK
          : (current >= 30 ? StockStatusTag.LOW : StockStatusTag.CRITICAL);

      MaterialStock stock = MaterialStock.builder()
          .projectId(projectId)
          .materialId(materialId)
          .openingStock(BigDecimal.valueOf(opening).setScale(3, RoundingMode.HALF_UP))
          .receivedMonth(BigDecimal.valueOf(receivedMonth).setScale(3, RoundingMode.HALF_UP))
          .issuedMonth(BigDecimal.valueOf(issuedMonth).setScale(3, RoundingMode.HALF_UP))
          .currentStock(BigDecimal.valueOf(current).setScale(3, RoundingMode.HALF_UP))
          .cumulativeConsumed(BigDecimal.valueOf(cumulativeConsumed).setScale(3, RoundingMode.HALF_UP))
          .wastagePercent(BigDecimal.valueOf(wastagePct).setScale(4, RoundingMode.HALF_UP))
          .stockValue(BigDecimal.valueOf(stockValue).setScale(2, RoundingMode.HALF_UP))
          .stockStatusTag(tag)
          .build();
      materialStockRepository.save(stock);
      created++;
      idx++;
      // binBays referenced for documentation / future use; storage_location is on Material itself.
      if (idx >= binBays.length) idx = 0;
    }
    return created;
  }

  // ─────────────────────────── 8. GRNs ──────────────────────────────

  private int seedGrns(UUID projectId, Map<String, UUID> materialIdByCode,
                       LocalDate from, LocalDate to, Random rng) {
    // 30 GRNs total — ~5 per supplier, distributed across the 90-day window.
    List<MaterialSource> sources = materialSourceRepository.findByProjectId(projectId);
    if (sources.isEmpty()) return 0;
    List<UUID> materialIds = new ArrayList<>(materialIdByCode.values());
    if (materialIds.isEmpty()) return 0;

    int totalGrns = 30;
    long days = java.time.temporal.ChronoUnit.DAYS.between(from, to);
    int created = 0;
    for (int i = 0; i < totalGrns; i++) {
      MaterialSource src = sources.get(i % sources.size());
      UUID materialId = materialIds.get(i % materialIds.size());
      LocalDate received = from.plusDays((long) (i * (days / (double) totalGrns)));
      String grnNo = String.format("BNK-GRN-2026-%03d", i + 1);
      // Skip if this GRN number already exists (idempotent).
      if (grnRepository.findByGrnNumber(grnNo).isPresent()) continue;

      // Stable per-(material, index) qty + rate — no RNG. The unitRate is anchored to a
      // small set of fixed reference rates per material so cost rollups reproduce.
      int matBucket = Math.abs(materialId.hashCode()) % 5;
      double[] refRates = {5.0, 12.5, 20.0, 35.0, 60.0};
      BigDecimal qty = BigDecimal.valueOf(20L + ((Math.abs(materialId.hashCode()) + i) % 180L))
          .setScale(3, RoundingMode.HALF_UP);
      BigDecimal unitRate = BigDecimal.valueOf(refRates[matBucket])
          .setScale(4, RoundingMode.HALF_UP);
      BigDecimal amount = qty.multiply(unitRate).setScale(2, RoundingMode.HALF_UP);

      GoodsReceiptNote grn = GoodsReceiptNote.builder()
          .projectId(projectId)
          .grnNumber(grnNo)
          .materialId(materialId)
          .receivedDate(received)
          .quantity(qty)
          .unitRate(unitRate)
          .amount(amount)
          .poNumber(String.format("BNK-PO-2026-%03d", i + 1))
          .vehicleNumber("OM-" + (1000 + (i * 37) % 9000))
          .acceptedQuantity(qty)
          .rejectedQuantity(BigDecimal.ZERO)
          .remarks("Supplier: " + src.getName() + " — INV-" + (10000 + i))
          .build();
      grnRepository.save(grn);
      created++;
    }
    return created;
  }

  // ─────────────────── 9. Material Reconciliation ───────────────────

  /**
   * One reconciliation per Feb/Mar/Apr 2026 + an aggregate "summary" period — for
   * a representative {@link Resource} (per-period anchor), tracking BOQ vs
   * procured vs issued vs remaining.
   */
  private int seedMaterialReconciliations(UUID projectId, Map<String, UUID> materialIdByCode,
                                          List<Resource> materials) {
    if (materialIdByCode.isEmpty()) return 0;
    // Use the first MATERIAL Resource as the anchor — simplest deterministic pick that satisfies
    // the not-null FK. The reconciliation period semantics are project-wide, not per-material.
    UUID anchorResourceId;
    if (!materials.isEmpty()) {
      anchorResourceId = materials.get(0).getId();
    } else {
      // No material resources from Agent 2 — skip rather than violate FK.
      log.warn("[BNK-DAILY] no MATERIAL resources found — skipping reconciliations");
      return 0;
    }
    String[] periods = {"2026-02", "2026-03", "2026-04", "SUMMARY"};
    int created = 0;
    double opening = 1500.0;
    for (int i = 0; i < periods.length; i++) {
      // Skip if this resource+period is already there (idempotent).
      if (materialReconciliationRepository
          .findByResourceIdAndPeriod(anchorResourceId, periods[i]).isPresent()) {
        continue;
      }
      double received = round2(800 + i * 50);
      double consumed = round2(720 + i * 60);
      double wastage = round2(consumed * 0.025);
      double closing = round2(opening + received - consumed - wastage);
      MaterialReconciliation row = MaterialReconciliation.builder()
          .resourceId(anchorResourceId)
          .projectId(projectId)
          .period(periods[i])
          .openingBalance(opening)
          .received(received)
          .consumed(consumed)
          .wastage(wastage)
          .closingBalance(closing)
          .unit("MT")
          .remarks("Period " + periods[i] + " — BOQ vs procured vs issued vs remaining")
          .build();
      materialReconciliationRepository.save(row);
      opening = closing;
      created++;
    }
    return created;
  }

  // ─────────────────────── 10. Equipment Logs ───────────────────────

  /**
   * Weekly equipment log — one row per equipment per week for 13 weeks. Mirrors
   * IcpmsEquipmentLogSeeder's row shape but with weekly windows so 34
   * equipment × 13 weeks ≈ 440 rows.
   */
  private int seedEquipmentLogs(UUID projectId, List<Resource> equipment, LocalDate from,
                                LocalDate to, List<Resource> labourPool, Random rng) {
    if (equipment.isEmpty()) {
      log.warn("[BNK-DAILY] no equipment Resources found — skipping equipment logs");
      return 0;
    }
    int weeks = 13;
    long totalDays = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    long daysPerWeek = Math.max(1L, totalDays / weeks);
    EquipmentStatus[] cycle = {EquipmentStatus.WORKING, EquipmentStatus.IDLE,
        EquipmentStatus.UNDER_MAINTENANCE, EquipmentStatus.WORKING};
    String[] sites = {"Section A — Km 0+000 to 10+000", "Section B — Km 10+000 to 20+000",
        "Section C — Km 20+000 to 30+000", "Section D — Km 30+000 to 41+000"};

    int created = 0;
    int eIdx = 0;
    for (Resource eq : equipment) {
      for (int w = 0; w < weeks; w++) {
        LocalDate weekStart = from.plusDays(w * daysPerWeek);
        if (weekStart.isAfter(to)) break;
        EquipmentStatus status = cycle[(eIdx + w) % cycle.length];
        double operating = 20 + rng.nextInt(31); // 20..50
        double idle = 2 + rng.nextInt(5);
        double breakdown = (status == EquipmentStatus.UNDER_MAINTENANCE) ? 4 + rng.nextInt(4) : 0;
        double fuel = round2(operating * 8.0);
        String operator = !labourPool.isEmpty()
            ? labourPool.get((eIdx + w) % labourPool.size()).getName()
            : "Site Operator " + ((eIdx % 12) + 1);
        String site = sites[(eIdx + w) % sites.length];

        EquipmentLog log = EquipmentLog.builder()
            .resourceId(eq.getId())
            .projectId(projectId)
            .logDate(weekStart)
            .deploymentSite(site)
            .operatingHours(operating)
            .idleHours((double) idle)
            .breakdownHours((double) breakdown)
            .fuelConsumed(fuel)
            .operatorName(operator)
            .status(status)
            .remarks(null)
            .build();
        equipmentLogRepository.save(log);
        created++;
      }
      eIdx++;
    }
    return created;
  }

  // ─────────────────────── 11. Labour Returns ───────────────────────

  private int seedLabourReturns(UUID projectId, List<LabourDesignation> designations,
                                List<LocalDate> workingDays, Random rng) {
    if (designations.isEmpty()) {
      log.warn("[BNK-DAILY] no LabourDesignation rows found — skipping labour returns");
      return 0;
    }
    // Pick first 10 by sort order — they're the canonical roles per
    // OmanLabourMasterSeeder ordering (PM, RE, SE, QS, Foreman, Mason, Carpenter, Helper, ...).
    List<LabourDesignation> active = designations.stream()
        .sorted(java.util.Comparator.comparing(LabourDesignation::getSortOrder,
            java.util.Comparator.nullsLast(Integer::compareTo))
            .thenComparing(LabourDesignation::getCode,
                java.util.Comparator.nullsLast(String::compareTo)))
        .limit(10)
        .toList();
    String contractor = "BNK Joint Venture";

    int created = 0;
    for (LabourDesignation d : active) {
      SkillCategory skill = mapSkill(d);
      // Per-designation baseline headcount band.
      int base = baselineHeadcountFor(d);
      for (int di = 0; di < workingDays.size(); di++) {
        LocalDate day = workingDays.get(di);
        int swing = (di % 5) - 2; // −2..+2
        int head = Math.max(1, base + swing);
        // Overtime: ~12% of days, 2h per labourer.
        boolean overtime = (di % 8 == 0);
        double manDays = head * 1.0 + (overtime ? head * 0.25 : 0.0);

        LabourReturn row = LabourReturn.builder()
            .projectId(projectId)
            .contractorName(contractor)
            .returnDate(day)
            .skillCategory(skill)
            .headCount(head)
            .manDays(round2(manDays))
            .siteLocation("Section " + SUPERVISORS[di % SUPERVISORS.length])
            .remarks(overtime ? d.getDesignation() + " — overtime engaged" : d.getDesignation())
            .build();
        labourReturnRepository.save(row);
        created++;
      }
    }
    // Suppress unused warning; rng kept for API parity with other seed methods.
    if (rng == null) {} // no-op
    return created;
  }

  private SkillCategory mapSkill(LabourDesignation d) {
    if (d.getCategory() == null) return SkillCategory.UNSKILLED;
    return switch (d.getCategory()) {
      case SITE_MANAGEMENT -> SkillCategory.SUPERVISOR;
      case PLANT_EQUIPMENT -> SkillCategory.SKILLED;
      case SKILLED_LABOUR -> SkillCategory.SKILLED;
      case SEMI_SKILLED_LABOUR -> SkillCategory.SEMI_SKILLED;
      case GENERAL_UNSKILLED -> SkillCategory.UNSKILLED;
    };
  }

  /** Baseline daily headcount per designation grade. */
  private int baselineHeadcountFor(LabourDesignation d) {
    if (d.getCategory() == null) return 6;
    return switch (d.getCategory()) {
      case SITE_MANAGEMENT -> 1;
      case PLANT_EQUIPMENT -> 6;
      case SKILLED_LABOUR -> 12;
      case SEMI_SKILLED_LABOUR -> 18;
      case GENERAL_UNSKILLED -> 25;
    };
  }

  // ─────────────────────────── Helpers ──────────────────────────────

  private List<LocalDate> workingDaysBetween(LocalDate from, LocalDate to) {
    List<LocalDate> out = new ArrayList<>();
    LocalDate d = from;
    while (!d.isAfter(to)) {
      DayOfWeek dow = d.getDayOfWeek();
      if (dow != DayOfWeek.FRIDAY && dow != DayOfWeek.SATURDAY) {
        out.add(d);
      }
      d = d.plusDays(1);
    }
    return out;
  }

  private List<LocalDate> calendarDaysBetween(LocalDate from, LocalDate to) {
    List<LocalDate> out = new ArrayList<>();
    LocalDate d = from;
    while (!d.isAfter(to)) {
      out.add(d);
      d = d.plusDays(1);
    }
    return out;
  }

  private static double round1(double v) {
    return Math.round(v * 10.0) / 10.0;
  }

  private static double round2(double v) {
    return Math.round(v * 100.0) / 100.0;
  }

  private static String truncate(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  // ─────────────────────────── Records ──────────────────────────────

  /** Catalogue entry used by both Material seeding and Stock seeding. */
  private record MaterialSpec(String code, String name, MaterialCategory category,
                              String unit, String grade) {}

  /** Plan §4.21 supplier descriptor. */
  private record SupplierSpec(String code, String name, MaterialSourceType type,
                              String region, int leadTimeDays, BigDecimal transportCost) {}
}
