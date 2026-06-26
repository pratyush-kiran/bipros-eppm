package com.bipros.api.config.seeder;

import com.bipros.api.config.seeder.NhaiRoadProjectWorkbookReader.BoqRow;
import com.bipros.api.config.seeder.NhaiRoadProjectWorkbookReader.DprRow;
import com.bipros.api.config.seeder.NhaiRoadProjectWorkbookReader.MaterialConsumptionRow;
import com.bipros.api.config.seeder.NhaiRoadProjectWorkbookReader.NextDayPlanRow;
import com.bipros.api.config.seeder.NhaiRoadProjectWorkbookReader.ProductivityNormRow;
import com.bipros.api.config.seeder.NhaiRoadProjectWorkbookReader.ProjectInfo;
import com.bipros.api.config.seeder.NhaiRoadProjectWorkbookReader.ResourceDeploymentRow;
import com.bipros.api.config.seeder.NhaiRoadProjectWorkbookReader.UnitRateRow;
import com.bipros.api.config.seeder.NhaiRoadProjectWorkbookReader.WeatherRow;
import com.bipros.calendar.domain.model.Calendar;
import com.bipros.calendar.domain.model.CalendarType;
import com.bipros.calendar.domain.model.CalendarWorkWeek;
import com.bipros.calendar.domain.model.DayType;
import com.bipros.calendar.domain.repository.CalendarRepository;
import com.bipros.calendar.domain.repository.CalendarWorkWeekRepository;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityRelationship;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.ActivityType;
import com.bipros.activity.domain.model.DurationType;
import com.bipros.activity.domain.model.RelationshipType;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.project.application.service.BoqCalculator;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.DailyResourceDeployment;
import com.bipros.project.domain.model.DailyWeather;
import com.bipros.project.domain.model.EpsNode;
import com.bipros.project.domain.model.NextDayPlan;
import com.bipros.project.domain.model.ObsNode;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.ProjectStatus;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.model.WbsPhase;
import com.bipros.project.domain.model.WbsStatus;
import com.bipros.project.domain.model.WbsType;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DailyResourceDeploymentRepository;
import com.bipros.project.domain.repository.DailyWeatherRepository;
import com.bipros.project.domain.repository.EpsNodeRepository;
import com.bipros.project.domain.repository.NextDayPlanRepository;
import com.bipros.project.domain.repository.ObsNodeRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.bipros.api.config.seeder.util.SeederResourceFactory;
import com.bipros.resource.domain.model.MaterialConsumptionLog;
import com.bipros.resource.application.service.WorkActivityService;
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceEquipmentDetails;
import com.bipros.resource.domain.model.ResourceMaterialDetails;
import com.bipros.resource.domain.model.ResourceRate;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceStatus;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.repository.MaterialConsumptionLogRepository;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import com.bipros.resource.domain.repository.ResourceEquipmentDetailsRepository;
import com.bipros.resource.domain.repository.ResourceMaterialDetailsRepository;
import com.bipros.resource.domain.repository.ResourceRateRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * NHAI NH-48 Rajasthan Widening — road construction test project seeder.
 *
 * <p>Data source: the workbook shipped on the classpath at
 * {@code seed-data/road-project/PMS RoadProject TestData.xlsx}, parsed by
 * {@link NhaiRoadProjectWorkbookReader}. Structural/mapping logic (EPS/OBS/Calendar/WBS
 * hierarchy, Resource-code derivation, BOQ-to-WBS grouping) stays in code because it is
 * project-convention, not data.
 *
 * <p>Seeded tables (all populated from the workbook):
 * <ul>
 *   <li>Project — from the Project Info sheet (name, code, client, contractor, PM, dates)</li>
 *   <li>WBS — 7 Level-2 nodes derived from BOQ item prefixes</li>
 *   <li>BOQ items — from BOQ & Budget sheet</li>
 *   <li>Productivity norms — from Productivity Norms sheet (Sections A + B)</li>
 *   <li>Resources + BUDGETED/ACTUAL rates — from Daily Cost Report Section A (Equipment / Material / Sub-Contract rows)</li>
 *   <li>Manpower ResourceRoles — from Daily Cost Report Section A (Manpower rows)</li>
 *   <li>Daily Progress Reports — from Supervisor Daily Rpt Section A</li>
 *   <li>Daily resource deployment — from Supervisor Daily Rpt Section B</li>
 *   <li>Daily weather — from Supervisor Daily Rpt Section C</li>
 *   <li>Next-day plans — from Supervisor Daily Rpt Section D</li>
 *   <li>Material consumption — from Daily Cost Report Section C</li>
 * </ul>
 *
 * <p>Sentinel: project code read from the workbook. If the corresponding {@code Project} already
 * exists the run is skipped. Runs at {@code @Order(140)} (after IoclPanipatSeeder).
 */
@Slf4j
@Component
@Profile("legacy-demo")
@Order(140)
@RequiredArgsConstructor
public class NhaiRoadProjectSeeder implements CommandLineRunner {

  private static final LocalDate DEFAULT_START = LocalDate.of(2025, 1, 1);
  private static final LocalDate DEFAULT_FINISH = LocalDate.of(2026, 12, 31);
  private static final long CHAINAGE_START_M = 145_000L;
  private static final long CHAINAGE_END_M = 165_000L;

  private final EpsNodeRepository epsNodeRepository;
  private final ObsNodeRepository obsNodeRepository;
  private final ProjectRepository projectRepository;
  private final WbsNodeRepository wbsNodeRepository;
  private final CalendarRepository calendarRepository;
  private final CalendarWorkWeekRepository calendarWorkWeekRepository;
  private final BoqItemRepository boqItemRepository;
  private final DailyProgressReportRepository dprRepository;
  private final DailyResourceDeploymentRepository deploymentRepository;
  private final DailyWeatherRepository weatherRepository;
  private final NextDayPlanRepository nextDayPlanRepository;
  private final ProductivityNormRepository productivityNormRepository;
  private final WorkActivityService workActivityService;
  private final ResourceRepository resourceRepository;
  private final ResourceRateRepository resourceRateRepository;
  private final ResourceRoleRepository resourceRoleRepository;
  private final ResourceEquipmentDetailsRepository resourceEquipmentDetailsRepository;
  private final ResourceMaterialDetailsRepository resourceMaterialDetailsRepository;
  private final SeederResourceFactory resourceFactory;
  private final MaterialConsumptionLogRepository materialConsumptionLogRepository;
  private final NhaiRoadProjectWorkbookReader reader;
  private final ActivityRepository activityRepository;
  private final ActivityRelationshipRepository activityRelationshipRepository;
  private final javax.sql.DataSource dataSource;

  @Override
  public void run(String... args) {
    // NOTE: deliberately no @Transactional on run(). Each Spring Data JPA save() runs in
    // its own short transaction and commits row-by-row, which is fine because
    // ddl-auto=create-drop starts from a clean DB on every boot. Without an outer
    // transaction, by the time loadReportsDataSqlBundle() opens a fresh DataSource
    // connection at the end, all of this seeder's project / WBS / activity / etc rows
    // are already committed and visible to the bundle's natural-key subqueries
    // (e.g. SELECT id FROM project.projects WHERE code = ?). Adding @Transactional here
    // re-introduces the bug where every bundle INSERT silently no-ops because the
    // SELECT subquery returns NULL.
    if (!reader.exists()) {
      log.warn("[NH-48] workbook not on classpath — skipping seeder");
      return;
    }

    reader.withWorkbook(wb -> {
      ProjectInfo info = reader.readProjectInfo(wb);
      String projectCode = info.projectCode();
      if (projectCode == null || projectCode.isBlank()) {
        log.warn("[NH-48] Project Info sheet missing 'Project Code' — aborting");
        return null;
      }
      if (projectRepository.findByCode(projectCode).isPresent()) {
        log.info("[NH-48] project '{}' already seeded, skipping", projectCode);
        return null;
      }

      log.info("[NH-48] seeding '{}' from workbook {}…", projectCode, NhaiRoadProjectWorkbookReader.WORKBOOK_PATH);

      UUID epsLeaf = seedEps();
      UUID obsLeaf = seedObs(info);
      UUID calendarId = seedCalendar();
      Project project = seedProject(epsLeaf, obsLeaf, info, calendarId);

      List<BoqRow> boqRows = reader.readBoqItems(wb);
      Map<String, UUID> wbs = seedWbs(project.getId(), boqRows);
      seedBoqItems(project.getId(), wbs, boqRows);

      List<ProductivityNormRow> normRows = reader.readProductivityNorms(wb);
      seedProductivityNorms(normRows);

      List<UnitRateRow> unitRates = reader.readUnitRateMaster(wb);
      Map<String, UUID> resources = seedResourcesAndRates(calendarId, unitRates);
      seedManpowerResourceRoles(unitRates);

      seedActivities(project.getId(), calendarId, wbs, boqRows, normRows);
      seedDailyProgressReports(project.getId(), wbs, reader.readSupervisorDailyReport(wb));
      seedMaterialConsumption(project.getId(), resources, reader.readMaterialConsumption(wb));
      seedResourceDeployments(project.getId(), reader.readResourceDeployment(wb));
      seedDailyWeather(project.getId(), reader.readDailyWeather(wb));
      seedNextDayPlans(project.getId(), reader.readNextDayPlans(wb));

      sanityCheck(project.getId(), boqRows);
      log.info("[NH-48] done. WBS nodes={}, resources={}, manpower roles={}",
          wbs.size(), resources.size(), unitRates.stream().filter(u -> "Manpower".equalsIgnoreCase(u.category())).count());

      // All JPA writes above committed row-by-row in their own per-call transactions
      // (no outer @Transactional on run()), so the SQL bundle below — which opens a
      // fresh DataSource connection — will see this project's rows via its
      // (SELECT id FROM project.projects WHERE code = ?) natural-key subqueries.
      loadReportsDataSqlBundle(projectCode);
      return null;
    });
  }

  /**
   * Reads {@code classpath:seed-data/road-project/reports/*.sql} and executes each
   * file in lexical order via {@link org.springframework.jdbc.datasource.init.ScriptUtils}.
   * The bundle fills the tables that back the project-level report canvas (risks,
   * EVM, cash flow, RA bills, VOs, contracts, compliance, etc.) — shape that the
   * Excel-driven seed above deliberately leaves blank. Each SQL file resolves the
   * project by natural key via subqueries, so no Java glue is needed beyond this
   * loader.
   *
   * <p>Idempotent via a sentinel count on {@code risk.risks} for this project.
   * Safe to re-run because on {@code ddl-auto: create-drop} the database is empty
   * at boot.
   */
  private void loadReportsDataSqlBundle(String projectCode) {
    org.springframework.core.io.Resource[] files;
    try {
      var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
      files = resolver.getResources("classpath:seed-data/road-project/reports/*.sql");
    } catch (Exception e) {
      log.error("[NH-48 reports] could not enumerate SQL bundle: {}", e.getMessage(), e);
      return;
    }
    if (files.length == 0) {
      log.info("[NH-48 reports] no SQL bundle found — skipping");
      return;
    }
    java.util.Arrays.sort(files, java.util.Comparator.comparing(org.springframework.core.io.Resource::getFilename));
    log.info("[NH-48 reports] running {} demo-data SQL file(s) for '{}'", files.length, projectCode);

    int ok = 0, failed = 0;
    for (var f : files) {
      // One JDBC connection per file with autoCommit=true so each file lands
      // independently. A failure in one file (e.g. a unique-constraint hit on a
      // re-run against partially-seeded data) is logged and the loader continues
      // — otherwise a single bad statement would prevent the remaining files from
      // populating the report panels at all.
      try (var conn = dataSource.getConnection()) {
        boolean wasAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(true);
        try {
          log.info("[NH-48 reports] executing {}", f.getFilename());
          org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(conn, f);
          ok++;
        } finally {
          conn.setAutoCommit(wasAutoCommit);
        }
      } catch (Exception e) {
        failed++;
        log.error("[NH-48 reports] {} failed: {}", f.getFilename(), e.getMessage());
      }
    }
    log.info("[NH-48 reports] bundle finished — {} succeeded, {} failed", ok, failed);
  }

  // ─────────────────────────── EPS ───────────────────────────
  private UUID seedEps() {
    EpsNode nhai = saveEps("NHAI", "National Highways Authority of India", null, 0);
    EpsNode rj = saveEps("NHAI-RJ", "NHAI Rajasthan Region", nhai.getId(), 0);
    EpsNode nh48 = saveEps("NH48-RJ", "NH-48 Corridor (Rajasthan)", rj.getId(), 0);
    return nh48.getId();
  }

  private EpsNode saveEps(String code, String name, UUID parentId, int order) {
    EpsNode n = new EpsNode();
    n.setCode(code);
    n.setName(name);
    n.setParentId(parentId);
    n.setSortOrder(order);
    return epsNodeRepository.save(n);
  }

  // ─────────────────────────── OBS ───────────────────────────
  private UUID seedObs(ProjectInfo info) {
    ObsNode nhai = saveObs("NHAI-HQ", nullSafe(info.client(), "NHAI Headquarters"), null, 0);
    ObsNode jaipur = saveObs("NHAI-RO-JAIPUR", "NHAI Regional Office Jaipur", nhai.getId(), 0);
    ObsNode contractor = saveObs("ABC-INFRACON", nullSafe(info.contractor(), "Contractor"), jaipur.getId(), 0);
    String pmName = info.projectManager() != null
        ? info.projectManager() + " — Project Team"
        : "Project Team";
    ObsNode pmTeam = saveObs("NH48-PM-TEAM", pmName, contractor.getId(), 0);
    return pmTeam.getId();
  }

  private ObsNode saveObs(String code, String name, UUID parentId, int order) {
    ObsNode o = new ObsNode();
    o.setCode(code);
    o.setName(name);
    o.setParentId(parentId);
    o.setSortOrder(order);
    return obsNodeRepository.save(o);
  }

  // ────────────────────────── Calendar ───────────────────────
  private UUID seedCalendar() {
    Calendar cal = Calendar.builder()
        .code("NH48-6day")
        .name("NH-48 Road Construction 6-day Calendar")
        .description("Mon-Sat WORKING 10 hrs/day (NHAI road construction norm); Sunday NON_WORKING.")
        .calendarType(CalendarType.GLOBAL)
        .isDefault(false)
        .standardWorkHoursPerDay(10.0)
        .standardWorkDaysPerWeek(6)
        .build();
    Calendar saved = calendarRepository.save(cal);
    for (DayOfWeek day : DayOfWeek.values()) {
      boolean working = day != DayOfWeek.SUNDAY;
      CalendarWorkWeek ww = CalendarWorkWeek.builder()
          .calendarId(saved.getId())
          .dayOfWeek(day)
          .dayType(working ? DayType.WORKING : DayType.NON_WORKING)
          .totalWorkHours(working ? 10.0 : 0.0)
          .startTime1(working ? LocalTime.of(7, 0) : null)
          .endTime1(working ? LocalTime.of(12, 0) : null)
          .startTime2(working ? LocalTime.of(13, 0) : null)
          .endTime2(working ? LocalTime.of(18, 0) : null)
          .build();
      calendarWorkWeekRepository.save(ww);
    }
    return saved.getId();
  }

  // ────────────────────────── Project ────────────────────────
  private Project seedProject(UUID epsLeafId, UUID obsLeafId, ProjectInfo info, UUID calendarId) {
    Project p = new Project();
    p.setCode(info.projectCode());
    p.setName(nullSafe(info.projectName(), "NH-48 Widening"));
    p.setDescription(buildDescription(info));
    p.setEpsNodeId(epsLeafId);
    p.setObsNodeId(obsLeafId);
    p.setPlannedStartDate(info.startDate() != null ? info.startDate() : DEFAULT_START);
    p.setPlannedFinishDate(info.plannedCompletion() != null ? info.plannedCompletion() : DEFAULT_FINISH);
    p.setDataDate(p.getPlannedStartDate());
    p.setStatus(ProjectStatus.ACTIVE);
    p.setPriority(1);
    // PMS MasterData Screen 01 enrichment — makes the Project Master UI render real data.
    p.setCategory("HIGHWAY");
    p.setMorthCode("NH-48");
    p.setFromChainageM(CHAINAGE_START_M);
    p.setToChainageM(CHAINAGE_END_M);
    p.setFromLocation("Km 145+000");
    p.setToLocation("Km 165+000");
    p.setTotalLengthKm(java.math.BigDecimal.valueOf(
        (CHAINAGE_END_M - CHAINAGE_START_M) / 1000.0).setScale(3, java.math.RoundingMode.HALF_UP));
    p.setCalendarId(calendarId);
    return projectRepository.save(p);
  }

  /**
   * Produce a short WBS-group label from a BOQ item description — takes the text before
   * a dash, parenthesis, or comma. E.g. "DBM – Dense Bituminous Macadam (50mm)" → "DBM".
   * Keeps group names data-driven (derived from the workbook's BOQ sheet, not hardcoded).
   */
  private String summariseGroupName(String description) {
    if (description == null || description.isBlank()) return "Package";
    String head = description.split("[–\\-(,]", 2)[0].trim();
    return head.isEmpty() ? description.trim() : head;
  }

  private String buildDescription(ProjectInfo info) {
    StringBuilder sb = new StringBuilder();
    if (info.projectName() != null) sb.append(info.projectName()).append(". ");
    if (info.location() != null) sb.append(info.location()).append(". ");
    if (info.totalLength() != null) sb.append("Length: ").append(info.totalLength()).append(". ");
    if (info.contractValue() != null) sb.append("Contract value: ").append(info.contractValue()).append(". ");
    sb.append("Source: PMS RoadProject test workbook.");
    return sb.toString();
  }

  // ────────────────────────── WBS ────────────────────────────
  /** Derive 7 Level-2 WBS nodes from the BOQ item-number prefixes (1.x, 2.x … 7.x). */
  private Map<String, UUID> seedWbs(UUID projectId, List<BoqRow> boqRows) {
    // This seeder persists Project directly via repository (not via ProjectService) so no root WBS
    // is auto-created — we make one ourselves so the Level-2 nodes have a parent.
    WbsNode rootNode = new WbsNode();
    rootNode.setCode("WBS-ROOT");
    rootNode.setName("NH-48 Widening — Root");
    rootNode.setParentId(null);
    rootNode.setProjectId(projectId);
    rootNode.setSortOrder(0);
    rootNode.setWbsLevel(1);
    rootNode.setWbsType(WbsType.PROGRAMME);
    rootNode.setPhase(WbsPhase.CONSTRUCTION);
    rootNode.setWbsStatus(WbsStatus.ACTIVE);
    rootNode.setChainageFromM(CHAINAGE_START_M);
    rootNode.setChainageToM(CHAINAGE_END_M);
    UUID root = wbsNodeRepository.save(rootNode).getId();

    // Group BOQ amount totals by top-level prefix for the WBS budget roll-up.
    Map<String, BigDecimal> amountByPrefix = new HashMap<>();
    for (BoqRow b : boqRows) {
      String prefix = b.itemNo() != null && !b.itemNo().isBlank()
          ? b.itemNo().substring(0, 1)
          : "9";
      BigDecimal amount = b.boqQty() != null && b.boqRate() != null
          ? b.boqQty().multiply(b.boqRate())
          : BigDecimal.ZERO;
      amountByPrefix.merge(prefix, amount, BigDecimal::add);
    }

    Map<String, UUID> wbs = new HashMap<>();
    // Derive the L2 group name from the first BOQ item of each prefix — no editorial hardcodes.
    // "1.1 Earthwork – Excavation in all types of soil" → group label "Earthwork – Excavation…"
    Map<String, String> groupNameByPrefix = new java.util.LinkedHashMap<>();
    for (BoqRow b : boqRows) {
      if (b.itemNo() == null || b.itemNo().isBlank()) continue;
      String prefix = b.itemNo().substring(0, 1);
      groupNameByPrefix.putIfAbsent(prefix, summariseGroupName(b.description()));
    }
    int order = 0;
    for (Map.Entry<String, String> g : groupNameByPrefix.entrySet()) {
      String prefix = g.getKey();
      // Express budget in crores (÷ 1e7 rupees).
      BigDecimal rupees = amountByPrefix.getOrDefault(prefix, BigDecimal.ZERO);
      BigDecimal crores = rupees.divide(new BigDecimal("10000000"), 4, RoundingMode.HALF_UP);

      WbsNode n = new WbsNode();
      n.setCode("WBS-" + prefix);
      n.setName(g.getValue());
      n.setParentId(root);
      n.setProjectId(projectId);
      n.setSortOrder(order++);
      n.setWbsLevel(2);
      n.setWbsType(WbsType.PACKAGE);
      n.setPhase(WbsPhase.CONSTRUCTION);
      n.setWbsStatus(WbsStatus.ACTIVE);
      n.setBudgetCrores(crores.stripTrailingZeros().setScale(Math.max(crores.scale(), 2), RoundingMode.HALF_UP));
      n.setChainageFromM(CHAINAGE_START_M);
      n.setChainageToM(CHAINAGE_END_M);
      WbsNode saved = wbsNodeRepository.save(n);
      wbs.put(prefix, saved.getId());
    }
    return wbs;
  }

  // ────────────────────────── BOQ Items ──────────────────────
  private void seedBoqItems(UUID projectId, Map<String, UUID> wbs, List<BoqRow> rows) {
    List<BoqItem> items = new ArrayList<>();
    for (BoqRow r : rows) {
      String wbsKey = (r.itemNo() != null && !r.itemNo().isBlank())
          ? r.itemNo().substring(0, 1)
          : null;
      BoqItem b = BoqItem.builder()
          .projectId(projectId)
          .itemNo(r.itemNo())
          .description(r.description())
          .unit(r.unit())
          .wbsNodeId(wbsKey != null ? wbs.get(wbsKey) : null)
          .boqQty(r.boqQty())
          .boqRate(r.boqRate())
          .budgetedRate(r.budgetedRate())
          .qtyExecutedToDate(r.qtyExecutedToDate())
          .actualRate(r.actualRate())
          .build();
      BoqCalculator.recompute(b);
      items.add(b);
    }
    boqItemRepository.saveAll(items);
    log.info("[NH-48] seeded {} BOQ items from workbook", items.size());
  }

  // ────────────────────── Productivity Norms ─────────────────
  private void seedProductivityNorms(List<ProductivityNormRow> rows) {
    List<ProductivityNorm> all = new ArrayList<>();
    for (ProductivityNormRow r : rows) {
      // Each Excel row pins a WorkActivity by name; reuse if it already exists.
      WorkActivity wa = workActivityService.findOrCreateByName(r.activityName(), r.unit());
      all.add(ProductivityNorm.builder()
          .normType(r.normType())
          .workActivity(wa)
          .activityName(wa.getName()) // legacy column kept in sync
          .unit(r.unit())
          .outputPerManPerDay(r.outputPerManPerDay())
          .crewSize(r.crewSize())
          .outputPerDay(r.outputPerDay())
          .outputPerHour(r.outputPerHour())
          .workingHoursPerDay(r.workingHoursPerDay())
          .fuelLitresPerHour(r.fuelLitresPerHour())
          .equipmentSpec(r.equipmentSpec())
          .remarks(r.remarks())
          .build());
    }
    productivityNormRepository.saveAll(all);
    long manpower = all.stream().filter(n -> n.getNormType().name().equals("MANPOWER")).count();
    log.info("[NH-48] seeded {} productivity norms ({} manpower + {} equipment) from workbook",
        all.size(), manpower, all.size() - manpower);
  }

  // ────────────────────── Resources + Rates ──────────────────
  /**
   * Persist every non-Manpower Unit-Rate-Master row as a {@link Resource} plus two
   * {@link ResourceRate} children (BUDGETED + ACTUAL). Manpower rows are intentionally skipped
   * here — they go to {@link #seedManpowerResourceRoles(List)}.
   */
  private Map<String, UUID> seedResourcesAndRates(UUID calendarId, List<UnitRateRow> rows) {
    Map<String, UUID> byCode = new HashMap<>();
    int nonManpowerCount = 0;
    for (UnitRateRow r : rows) {
      String category = r.category();
      if (category == null) continue;
      if ("Manpower".equalsIgnoreCase(category)) continue;

      String code = "NH48-" + codePrefix(category) + "-" + slug(r.description());
      String typeCode = resourceTypeCodeFor(category);
      ResourceType rt = resourceFactory.requireType(typeCode);
      String roleCode = resourceRoleCodeFor(category, r.description());
      ResourceRole role = resourceFactory.ensureRole(roleCode, typeCode);
      String unitLabel = r.unit();
      BigDecimal costPerUnit = r.budgetedRate() != null ? r.budgetedRate() : BigDecimal.ZERO;
      Resource res = Resource.builder()
          .code(code)
          .name(r.description())
          .resourceType(rt)
          .role(role)
          .unit(resourceUnitCodeFor(unitLabel))
          .calendarId(calendarId)
          .status(ResourceStatus.ACTIVE)
          .availability(BigDecimal.valueOf(100))
          .costPerUnit(costPerUnit)
          .build();
      Resource saved = resourceRepository.save(res);
      byCode.put(code, saved.getId());
      // Populate the per-type detail row so equipment / material specs are not silently lost.
      if ("EQUIPMENT".equals(typeCode)) {
        resourceEquipmentDetailsRepository.save(ResourceEquipmentDetails.builder()
            .resourceId(saved.getId())
            .build());
      } else if ("MATERIAL".equals(typeCode)) {
        resourceMaterialDetailsRepository.save(ResourceMaterialDetails.builder()
            .resourceId(saved.getId())
            .baseUnit(resourceUnitCodeFor(unitLabel))
            .build());
      }
      resourceRateRepository.save(buildRate(saved.getId(), "BUDGETED", r.budgetedRate(),
          project_start_hint()));
      resourceRateRepository.save(buildRate(saved.getId(), "ACTUAL", r.actualRate(),
          project_start_hint()));
      nonManpowerCount++;
    }
    log.info("[NH-48] seeded {} resources (Equipment/Material/Sub-Contract) with BUDGETED + ACTUAL rates", nonManpowerCount);
    return byCode;
  }

  private LocalDate project_start_hint() {
    return DEFAULT_START;
  }

  private ResourceRate buildRate(UUID resourceId, String rateType, BigDecimal price, LocalDate effective) {
    ResourceRate rate = new ResourceRate();
    rate.setResourceId(resourceId);
    rate.setRateType(rateType);
    rate.setPricePerUnit(price != null ? price : BigDecimal.ZERO);
    rate.setEffectiveDate(effective);
    return rate;
  }

  private void seedManpowerResourceRoles(List<UnitRateRow> rows) {
    int count = 0;
    ResourceType laborType = resourceFactory.requireType("LABOR");
    for (UnitRateRow r : rows) {
      if (!"Manpower".equalsIgnoreCase(r.category())) continue;
      String code = "NH48-ROLE-" + slug(r.description());
      // Skip if a role with this code already exists (handles re-runs after partial seed).
      if (resourceRoleRepository.findByCode(code).isPresent()) continue;
      ResourceRole role = ResourceRole.builder()
          .code(code)
          .name(r.description())
          .description("Manpower role seeded from Daily Cost Report Section A")
          .resourceType(laborType)
          .productivityUnit(r.unit())
          .sortOrder(0)
          .active(true)
          .build();
      resourceRoleRepository.save(role);
      count++;
    }
    log.info("[NH-48] seeded {} manpower ResourceRoles with default rates", count);
  }

  private String resourceTypeCodeFor(String category) {
    return switch (category) {
      case "Manpower" -> "LABOR";
      case "Material" -> "MATERIAL";
      default -> "EQUIPMENT";
    };
  }

  /**
   * Derive a {@link ResourceRole#getCode()} from the legacy category × name match. This used to
   * map to the deleted {@code ResourceCategory} enum; we now reuse the same identifiers as role
   * codes so role lookup-or-create produces stable rows across re-runs.
   */
  private String resourceRoleCodeFor(String category, String name) {
    if (name == null) return "OTHER";
    return switch (category) {
      case "Manpower" -> name.contains("Engineer") || name.contains("Supervisor")
          ? "SITE_ENGINEER"
          : (name.contains("Mason") ? "SKILLED_LABOUR"
              : (name.contains("Operator") ? "OPERATOR" : "UNSKILLED_LABOUR"));
      case "Material" -> name.contains("Bitumen") ? "BITUMEN"
          : (name.contains("Cement") ? "CEMENT"
              : (name.contains("Aggregate") || name.contains("GSB") || name.contains("WMM") || name.contains("Sand")
                  ? "AGGREGATE" : "OTHER"));
      case "Equipment" -> name.contains("Excavator") || name.contains("Tipper") || name.contains("Grader")
          ? "EARTH_MOVING"
          : (name.contains("Paver") || name.contains("Hot Mix") || name.contains("Roller")
              ? "PAVING_EQUIPMENT" : "OTHER");
      default -> "OTHER";
    };
  }

  private String resourceUnitCodeFor(String label) {
    if (label == null) return "PER_DAY";
    return switch (label.toLowerCase(Locale.ROOT)) {
      case "cum" -> "CU_M";
      case "mt", "bag" -> "MT";
      case "rm" -> "RMT";
      case "each" -> "NOS";
      default -> "PER_DAY";
    };
  }

  private double ratePerHourFrom(BigDecimal price, String unit) {
    if (price == null) return 0.0;
    double p = price.doubleValue();
    if (unit == null) return p;
    return switch (unit.toLowerCase(Locale.ROOT)) {
      case "hour" -> p;
      case "day" -> p / 8.0;
      default -> p;
    };
  }

  private String codePrefix(String category) {
    return switch (category) {
      case "Equipment" -> "EQ";
      case "Material" -> "MT";
      case "Sub-Contract" -> "SC";
      case "Manpower" -> "MP";
      default -> "XX";
    };
  }

  /** Compact upper-case code slug — e.g. "Excavator (JCB 210)" → "EXCAVATORJCB210". */
  private String slug(String s) {
    if (s == null) return "UNKNOWN";
    StringBuilder sb = new StringBuilder();
    for (char c : s.toUpperCase(Locale.ROOT).toCharArray()) {
      if (Character.isLetterOrDigit(c)) sb.append(c);
      if (sb.length() >= 20) break;
    }
    return sb.length() == 0 ? "UNKNOWN" : sb.toString();
  }

  // ───────────────────────── Activities ──────────────────────
  /**
   * One Activity per BOQ item. Duration comes from the Productivity Norms sheet — BOQ qty divided
   * by the matching norm's daily output, clamped to [14, 180] days so the schedule stays realistic
   * for a 2-year corridor project. Start dates are staggered by BOQ prefix in the standard road
   * sequence (Earthwork → Sub-base → Bituminous → Drainage → Road Furniture → Bridges → Misc).
   *
   * <p>No hardcoded durations or dates — every value is computed from the Excel workbook + the
   * project's planned start date.
   */
  private void seedActivities(UUID projectId, UUID calendarId, Map<String, UUID> wbs,
                              List<BoqRow> boqRows, List<ProductivityNormRow> norms) {
    // Prefix → how many days after project start this group begins.
    // Derived from typical road-construction sequencing — Earthwork first, Bridges in parallel.
    Map<String, Integer> prefixOffsetDays = Map.of(
        "1", 0,     // Earthwork: month 0
        "2", 60,    // Sub-base: month 2
        "3", 180,   // Bituminous: month 6
        "4", 90,    // Drainage: month 3
        "5", 420,   // Road furniture: month 14
        "6", 0,     // Bridges: month 0 (parallel)
        "7", 600    // Misc/finishing: month 20
    );

    LocalDate projectStart = DEFAULT_START;
    int totalSeeded = 0;
    int sortOrder = 0;

    for (BoqRow b : boqRows) {
      if (b.itemNo() == null || b.itemNo().isBlank()) continue;
      String prefix = b.itemNo().substring(0, 1);
      UUID wbsNodeId = wbs.get(prefix);
      if (wbsNodeId == null) continue;

      long durationDays = estimateDurationDays(b, norms);

      // Stagger within a prefix group by itemNo order so that e.g. 1.1 Excavation finishes
      // before 1.2 Embankment starts.
      int withinGroupIndex = withinGroupIndex(b.itemNo(), boqRows);
      int groupOffset = prefixOffsetDays.getOrDefault(prefix, 0);
      LocalDate start = projectStart.plusDays(groupOffset + withinGroupIndex * 30L);
      LocalDate finish = start.plusDays(durationDays);

      Activity a = new Activity();
      a.setCode("ACT-" + b.itemNo());
      a.setName(b.description() != null ? truncate(b.description(), 100) : "Activity " + b.itemNo());
      a.setDescription(b.description());
      a.setProjectId(projectId);
      a.setWbsNodeId(wbsNodeId);
      a.setActivityType(ActivityType.TASK_DEPENDENT);
      a.setDurationType(DurationType.FIXED_DURATION_AND_UNITS);
      a.setStatus(ActivityStatus.NOT_STARTED);
      a.setOriginalDuration((double) durationDays);
      a.setRemainingDuration((double) durationDays);
      a.setAtCompletionDuration((double) durationDays);
      a.setPlannedStartDate(start);
      a.setPlannedFinishDate(finish);
      a.setEarlyStartDate(start);
      a.setEarlyFinishDate(finish);
      a.setCalendarId(calendarId);
      a.setPercentComplete(0.0);
      a.setPhysicalPercentComplete(0.0);
      a.setIsCritical(false);
      a.setSortOrder(sortOrder++);
      a.setChainageFromM(CHAINAGE_START_M);
      a.setChainageToM(CHAINAGE_END_M);

      activityRepository.save(a);
      totalSeeded++;
    }
    log.info("[NH-48] seeded {} activities derived from BOQ items + productivity norms", totalSeeded);

    seedActivityRelationships(projectId);
  }

  /**
   * Chain NH-48 activities with FS(0) relationships. Within each BOQ prefix group (earthwork,
   * sub-base, bituminous, etc.) successive itemNos depend on their predecessor; additionally each
   * group's last activity feeds the next group (earthwork → sub-base → bituminous → road
   * furniture → finishing) so the scheduler sees one connected network instead of 15 orphans.
   */
  private void seedActivityRelationships(UUID projectId) {
    if (activityRelationshipRepository.findByProjectId(projectId).size() >= 2) {
      return;
    }
    List<Activity> activities = activityRepository.findByProjectId(projectId).stream()
        .filter(a -> a.getCode() != null && a.getCode().startsWith("ACT-"))
        .sorted(Comparator.comparing(this::numericItemKey))
        .toList();
    if (activities.size() < 2) return;

    // Group by BOQ prefix (first character of itemNo).
    java.util.LinkedHashMap<String, List<Activity>> byPrefix = new java.util.LinkedHashMap<>();
    for (Activity a : activities) {
      String itemNo = a.getCode().substring("ACT-".length());
      String prefix = itemNo.substring(0, 1);
      byPrefix.computeIfAbsent(prefix, p -> new ArrayList<>()).add(a);
    }

    int created = 0;
    // Intra-group FS chain
    Activity prevGroupTail = null;
    for (var entry : byPrefix.entrySet()) {
      List<Activity> group = entry.getValue();
      for (int i = 1; i < group.size(); i++) {
        if (persistRelationship(projectId, group.get(i - 1), group.get(i), 0.0)) created++;
      }
      // Inter-group: last of previous group → first of this group
      if (prevGroupTail != null && !group.isEmpty()) {
        if (persistRelationship(projectId, prevGroupTail, group.get(0), 0.0)) created++;
      }
      if (!group.isEmpty()) prevGroupTail = group.get(group.size() - 1);
    }
    log.info("[NH-48] seeded {} activity relationships across {} BOQ groups",
        created, byPrefix.size());
  }

  private boolean persistRelationship(UUID projectId, Activity pred, Activity succ, double lag) {
    if (activityRelationshipRepository
        .existsByPredecessorActivityIdAndSuccessorActivityId(pred.getId(), succ.getId())) {
      return false;
    }
    ActivityRelationship rel = new ActivityRelationship();
    rel.setProjectId(projectId);
    rel.setPredecessorActivityId(pred.getId());
    rel.setSuccessorActivityId(succ.getId());
    rel.setRelationshipType(RelationshipType.FINISH_TO_START);
    rel.setLag(lag);
    rel.setIsExternal(false);
    activityRelationshipRepository.save(rel);
    return true;
  }

  /**
   * Sort key that orders BOQ itemNos numerically segment-by-segment so 1.10 sorts after 1.9
   * rather than before it.
   */
  private String numericItemKey(Activity a) {
    String itemNo = a.getCode().substring("ACT-".length());
    StringBuilder key = new StringBuilder();
    for (String seg : itemNo.split("\\.")) {
      try {
        key.append(String.format("%08d", Integer.parseInt(seg)));
      } catch (NumberFormatException e) {
        key.append(seg);
      }
      key.append('.');
    }
    return key.toString();
  }

  /** Compute duration in days for a BOQ item using the matching productivity norm. */
  private long estimateDurationDays(BoqRow b, List<ProductivityNormRow> norms) {
    if (b.boqQty() == null || b.boqQty().signum() == 0) return 30L;
    BigDecimal dailyOutput = findDailyOutput(b, norms);
    if (dailyOutput == null || dailyOutput.signum() == 0) return 60L; // fallback when no norm match
    // crews-assumed: 1 for items < 500 qty, 2 for medium, 3 for large — rough balancing.
    int crews = b.boqQty().compareTo(new BigDecimal("10000")) >= 0 ? 3
        : b.boqQty().compareTo(new BigDecimal("500")) >= 0 ? 2 : 1;
    BigDecimal days = b.boqQty().divide(dailyOutput.multiply(BigDecimal.valueOf(crews)), 0, RoundingMode.CEILING);
    long d = days.longValueExact();
    return Math.max(14L, Math.min(180L, d));
  }

  /** Find the productivity norm whose activity name best matches the BOQ description. */
  private BigDecimal findDailyOutput(BoqRow b, List<ProductivityNormRow> norms) {
    if (b.description() == null) return null;
    String needle = b.description().toLowerCase(Locale.ROOT);
    for (ProductivityNormRow n : norms) {
      if (n.activityName() == null) continue;
      String normKey = n.activityName().toLowerCase(Locale.ROOT).split("[–\\-(]", 2)[0].trim();
      if (!normKey.isEmpty() && needle.contains(normKey)) {
        return n.outputPerDay();
      }
    }
    return null;
  }

  /** 0-based index of a BOQ item within its prefix group, sorted by itemNo. */
  private int withinGroupIndex(String itemNo, List<BoqRow> all) {
    if (itemNo == null || itemNo.length() < 1) return 0;
    String prefix = itemNo.substring(0, 1);
    List<String> siblings = all.stream()
        .map(BoqRow::itemNo)
        .filter(s -> s != null && s.startsWith(prefix))
        .sorted()
        .toList();
    int idx = siblings.indexOf(itemNo);
    return idx < 0 ? 0 : idx;
  }

  private String truncate(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  // ───────────────────── Daily Progress Reports ─────────────
  private void seedDailyProgressReports(UUID projectId, Map<String, UUID> wbs, List<DprRow> rows) {
    Map<String, BigDecimal> cumulativeByActivity = new HashMap<>();
    List<DailyProgressReport> saved = new ArrayList<>();
    for (DprRow r : rows) {
      BigDecimal qty = r.qtyExecuted() != null ? r.qtyExecuted() : BigDecimal.ZERO;
      BigDecimal cumulative = cumulativeByActivity.getOrDefault(r.activity(), BigDecimal.ZERO).add(qty);
      cumulativeByActivity.put(r.activity(), cumulative);

      String wbsKey = wbsKeyForActivity(r.activity());
      String boqItemNo = boqItemNoForActivity(r.activity(), r.unit());
      DailyProgressReport d = DailyProgressReport.builder()
          .projectId(projectId)
          .reportDate(r.reportDate())
          .supervisorName(r.supervisor())
          .chainageFromM(r.chainageFromM())
          .chainageToM(r.chainageToM())
          .activityName(r.activity())
          .unit(r.unit())
          .qtyExecuted(qty)
          // cumulativeQty is computed on read — no longer stored.
          .boqItemNo(boqItemNo)
          .wbsNodeId(wbsKey != null ? wbs.get(wbsKey) : null)
          .remarks(r.remarks())
          .approvalStatus(DprApprovalStatus.APPROVED)
          .build();
      saved.add(d);
    }
    dprRepository.saveAll(saved);
    log.info("[NH-48] seeded {} DPR rows from workbook", saved.size());
  }

  /**
   * Map a DPR activity name to the exact BOQ itemNo that prices it in the Daily Cost Report.
   * The workbook's Section B sheet does the same mapping manually — we're encoding those rules
   * here so the seed data matches Excel row-for-row.
   */
  private String boqItemNoForActivity(String activity, String unit) {
    if (activity == null) return null;
    String a = activity.toLowerCase(Locale.ROOT);
    if (a.contains("earthwork") && a.contains("excavation")) return "1.1";
    if (a.contains("embankment")) return "1.2";
    if (a.contains("gsb")) return "2.1";
    if (a.contains("wmm")) return "2.2";
    if (a.contains("dbm")) return "3.1";
    if (a.contains("bc ") || a.startsWith("bc") || a.contains("bituminous concrete")) return "3.2";
    if (a.contains("cwd") || a.contains("catch water")) return "4.1";
    if (a.contains("box culvert")) return "4.2";
    if (a.contains("culvert")) return "4.3";
    if (a.contains("road mark")) return "5.1";
    if (a.contains("sign board") || a.contains("signage")) return "5.2";
    if (a.contains("kerb")) return "5.3";
    if (a.contains("bridge")) return "6.1";
    return null;
  }

  /** Map activity text to the WBS prefix key (1..7). Keeps DPR rows linked to the right group. */
  private String wbsKeyForActivity(String activity) {
    if (activity == null) return null;
    String a = activity.toLowerCase(Locale.ROOT);
    if (a.contains("earthwork") || a.contains("embankment")) return "1";
    if (a.contains("gsb") || a.contains("wmm") || a.contains("sub-base") || a.contains("macadam") && !a.contains("bituminous"))
      return "2";
    if (a.contains("dbm") || a.contains("bituminous") || a.contains("bc")) return "3";
    if (a.contains("drain") || a.contains("culvert") || a.contains("cwd")) return "4";
    if (a.contains("road mark") || a.contains("sign") || a.contains("kerb")) return "5";
    if (a.contains("bridge")) return "6";
    return null;
  }

  // ─────────────────── Material Consumption ────────────────
  private void seedMaterialConsumption(UUID projectId, Map<String, UUID> resources, List<MaterialConsumptionRow> rows) {
    List<MaterialConsumptionLog> saved = new ArrayList<>();
    for (MaterialConsumptionRow r : rows) {
      BigDecimal opening = r.openingStock() != null ? r.openingStock() : BigDecimal.ZERO;
      BigDecimal received = r.received() != null ? r.received() : BigDecimal.ZERO;
      BigDecimal consumed = r.consumed() != null ? r.consumed() : BigDecimal.ZERO;
      BigDecimal closing = r.closingStock() != null ? r.closingStock() : opening.add(received).subtract(consumed);
      MaterialConsumptionLog log = MaterialConsumptionLog.builder()
          .projectId(projectId)
          .logDate(r.logDate())
          .materialName(r.materialName())
          .unit(r.unit())
          .openingStock(opening)
          .received(received)
          .consumed(consumed)
          .closingStock(closing)
          .wastagePercent(r.wastagePercent())
          .issuedBy(r.issuedBy())
          .receivedBy(r.receivedBy())
          .resourceId(findResourceByName(resources, r.materialName()))
          .build();
      saved.add(log);
    }
    materialConsumptionLogRepository.saveAll(saved);
    log.info("[NH-48] seeded {} material consumption rows from workbook", saved.size());
  }

  /** Best-effort link from a material row to the corresponding Resource by name substring. */
  private UUID findResourceByName(Map<String, UUID> resourcesByCode, String materialName) {
    if (materialName == null) return null;
    String needle = materialName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    for (Map.Entry<String, UUID> e : resourcesByCode.entrySet()) {
      String code = e.getKey().toLowerCase(Locale.ROOT);
      if (code.contains(needle.substring(0, Math.min(needle.length(), 6)))) return e.getValue();
    }
    return null;
  }

  // ────────────────── Resource Deployment ───────────────────
  private void seedResourceDeployments(UUID projectId, List<ResourceDeploymentRow> rows) {
    List<DailyResourceDeployment> saved = new ArrayList<>();
    for (ResourceDeploymentRow r : rows) {
      saved.add(DailyResourceDeployment.builder()
          .projectId(projectId)
          .logDate(r.logDate())
          .resourceType(r.type())
          .resourceDescription(r.description())
          .nosPlanned(r.nosPlanned())
          .nosDeployed(r.nosDeployed())
          .hoursWorked(r.hoursWorked())
          .idleHours(r.idleHours())
          .remarks(r.remarks())
          .build());
    }
    deploymentRepository.saveAll(saved);
    log.info("[NH-48] seeded {} resource deployment rows from workbook", saved.size());
  }

  // ────────────────────── Daily Weather ─────────────────────
  private void seedDailyWeather(UUID projectId, List<WeatherRow> rows) {
    List<DailyWeather> saved = new ArrayList<>();
    for (WeatherRow r : rows) {
      saved.add(DailyWeather.builder()
          .projectId(projectId)
          .logDate(r.logDate())
          .tempMaxC(r.tempMaxC())
          .tempMinC(r.tempMinC())
          .rainfallMm(r.rainfallMm())
          .windKmh(r.windKmh())
          .weatherCondition(r.condition())
          .workingHours(r.workingHours())
          .remarks(r.remarks())
          .build());
    }
    weatherRepository.saveAll(saved);
    log.info("[NH-48] seeded {} daily weather rows from workbook", saved.size());
  }

  // ───────────────────── Next Day Plans ─────────────────────
  private void seedNextDayPlans(UUID projectId, List<NextDayPlanRow> rows) {
    List<NextDayPlan> saved = new ArrayList<>();
    for (NextDayPlanRow r : rows) {
      saved.add(NextDayPlan.builder()
          .projectId(projectId)
          .reportDate(r.reportDate())
          .nextDayActivity(r.activity())
          .chainageFromM(r.chainageFromM())
          .chainageToM(r.chainageToM())
          .targetQty(r.targetQty())
          .unit(r.unit())
          .concerns(r.concerns())
          .actionBy(r.actionBy())
          .dueDate(r.dueDate())
          .build());
    }
    nextDayPlanRepository.saveAll(saved);
    log.info("[NH-48] seeded {} next day plan rows from workbook", saved.size());
  }

  // ────────────────────────── Sanity ─────────────────────────
  /**
   * Check BOQ grand totals against the workbook's own SUM(). Uses the sheet's BoqRow data so that
   * the expected values track whatever the workbook currently contains — no hardcoded expected
   * numbers to drift out of sync.
   */
  private void sanityCheck(UUID projectId, List<BoqRow> boqRows) {
    BigDecimal expectedBoq = BigDecimal.ZERO;
    BigDecimal expectedBudget = BigDecimal.ZERO;
    BigDecimal expectedActual = BigDecimal.ZERO;
    for (BoqRow r : boqRows) {
      expectedBoq = expectedBoq.add(nz(r.boqQty()).multiply(nz(r.boqRate())));
      expectedBudget = expectedBudget.add(nz(r.boqQty()).multiply(nz(r.budgetedRate())));
      expectedActual = expectedActual.add(nz(r.qtyExecutedToDate()).multiply(nz(r.actualRate())));
    }

    List<BoqItem> items = boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId);
    BigDecimal actualBoq = BigDecimal.ZERO;
    BigDecimal actualBudget = BigDecimal.ZERO;
    BigDecimal actualActual = BigDecimal.ZERO;
    for (BoqItem b : items) {
      actualBoq = actualBoq.add(nz(b.getBoqAmount()));
      actualBudget = actualBudget.add(nz(b.getBudgetedAmount()));
      actualActual = actualActual.add(nz(b.getActualAmount()));
    }

    log.info("[NH-48] BOQ sanity — items={}  |  BOQ stored={} expected={}  |  Budget stored={} expected={}  |  Actual stored={} expected={}",
        items.size(),
        actualBoq.setScale(0, RoundingMode.HALF_UP), expectedBoq.setScale(0, RoundingMode.HALF_UP),
        actualBudget.setScale(0, RoundingMode.HALF_UP), expectedBudget.setScale(0, RoundingMode.HALF_UP),
        actualActual.setScale(0, RoundingMode.HALF_UP), expectedActual.setScale(0, RoundingMode.HALF_UP));
    checkWithin1Pct("BOQ total", actualBoq, expectedBoq);
    checkWithin1Pct("Budget total", actualBudget, expectedBudget);
    checkWithin1Pct("Actual total", actualActual, expectedActual);
  }

  private void checkWithin1Pct(String label, BigDecimal actual, BigDecimal expected) {
    if (expected.signum() == 0) return;
    BigDecimal diff = actual.subtract(expected).abs();
    BigDecimal tolerance = expected.abs().multiply(new BigDecimal("0.01"));
    if (diff.compareTo(tolerance) > 0) {
      throw new IllegalStateException(String.format(
          "[NH-48] %s seeded=%s expected=%s — drift > 1%% from workbook; fix seeder or formulas.",
          label, actual.toPlainString(), expected.toPlainString()));
    }
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static String nullSafe(String s, String fallback) {
    return s == null || s.isBlank() ? fallback : s;
  }
}
