package com.bipros.api.config.seeder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityRelationship;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.ActivityType;
import com.bipros.activity.domain.model.DurationType;
import com.bipros.activity.domain.model.RelationshipType;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.api.config.seeder.OmanRoadProjectWorkbookReader.BoqRow;
import com.bipros.api.config.seeder.OmanRoadProjectWorkbookReader.DirectLabourRow;
import com.bipros.api.config.seeder.OmanRoadProjectWorkbookReader.IndirectStaffRow;
import com.bipros.api.config.seeder.OmanRoadProjectWorkbookReader.ProductivityNormRow;
import com.bipros.api.config.seeder.OmanRoadProjectWorkbookReader.ProjectInfo;
import com.bipros.calendar.domain.model.Calendar;
import com.bipros.calendar.domain.model.CalendarType;
import com.bipros.calendar.domain.model.CalendarWorkWeek;
import com.bipros.calendar.domain.model.DayType;
import com.bipros.calendar.domain.repository.CalendarRepository;
import com.bipros.calendar.domain.repository.CalendarWorkWeekRepository;
import com.bipros.project.application.service.BoqCalculator;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.model.EpsNode;
import com.bipros.project.domain.model.ObsNode;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.ProjectStatus;
import com.bipros.project.domain.model.Stretch;
import com.bipros.project.domain.model.StretchActivityLink;
import com.bipros.project.domain.model.StretchStatus;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.model.WbsPhase;
import com.bipros.project.domain.model.WbsStatus;
import com.bipros.project.domain.model.WbsType;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.EpsNodeRepository;
import com.bipros.project.domain.repository.ObsNodeRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.StretchActivityLinkRepository;
import com.bipros.project.domain.repository.StretchRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.bipros.api.config.seeder.util.SeederResourceFactory;
import com.bipros.resource.application.service.WorkActivityService;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import com.bipros.resource.domain.model.LabourDesignation;
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.ProductivityNormType;
import com.bipros.resource.domain.model.ProjectLabourDeployment;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceEquipmentDetails;
import com.bipros.resource.domain.model.ResourceMaterialDetails;
import com.bipros.resource.domain.model.ResourceRate;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceStatus;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.repository.LabourDesignationRepository;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import com.bipros.resource.domain.repository.ProjectLabourDeploymentRepository;
import com.bipros.resource.domain.repository.ResourceEquipmentDetailsRepository;
import com.bipros.resource.domain.repository.ResourceMaterialDetailsRepository;
import com.bipros.resource.domain.repository.ResourceRateRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Oman Barka–Nakhal Road Project (code 6155) — main project-core seeder.
 *
 * <p>Mirrors {@link NhaiRoadProjectSeeder} verbatim in shape: opens the 3 Oman
 * workbooks via {@link OmanRoadProjectWorkbookReader}, then seeds EPS / OBS /
 * Calendar / Project / WBS / BOQ / Productivity Norms / Resources (Equipment +
 * Material) / Manpower Roles / ProjectLabourDeployment / Activities /
 * Activity Relationships / Stretches.
 *
 * <p>Daily DPRs/Weather/Deployment, EVM, RA bills, contracts, risks and the
 * supplemental bundle (drawings, RFIs, baselines, attachments) are owned by
 * other seeders/agents — this class only seeds the project-core spine.
 *
 * <p>Currency: OMR throughout. Quantities & rates are taken literally from the
 * workbook when present; #REF!/blank cells are replaced with deterministic
 * synthetic values seeded from the project code "6155" so reruns produce
 * identical data.
 */
@Slf4j
@Component
@Profile("seed")
@ConditionalOnProperty(name = "seeders.legacy.enabled", havingValue = "true", matchIfMissing = true)
@Order(141)
@RequiredArgsConstructor
public class OmanRoadProjectSeeder implements CommandLineRunner {

  private static final String PROJECT_CODE = "6155";
  private static final LocalDate DEFAULT_START = LocalDate.of(2024, 9, 1);
  private static final LocalDate DEFAULT_FINISH = LocalDate.of(2026, 8, 31);
  private static final LocalDate DEFAULT_DATA_DATE = LocalDate.of(2026, 4, 29);
  private static final long CHAINAGE_START_M = 0L;
  private static final long CHAINAGE_END_M = 41_000L;
  private static final long DETERMINISTIC_SEED = 6155L;

  private final EpsNodeRepository epsNodeRepository;
  private final ObsNodeRepository obsNodeRepository;
  private final ProjectRepository projectRepository;
  private final WbsNodeRepository wbsNodeRepository;
  private final CalendarRepository calendarRepository;
  private final CalendarWorkWeekRepository calendarWorkWeekRepository;
  private final BoqItemRepository boqItemRepository;
  private final ProductivityNormRepository productivityNormRepository;
  private final WorkActivityService workActivityService;
  private final WorkActivityRepository workActivityRepository;
  private final ResourceRepository resourceRepository;
  private final ResourceRateRepository resourceRateRepository;
  private final ResourceRoleRepository resourceRoleRepository;
  private final ResourceEquipmentDetailsRepository resourceEquipmentDetailsRepository;
  private final ResourceMaterialDetailsRepository resourceMaterialDetailsRepository;
  private final SeederResourceFactory resourceFactory;
  private final ActivityRepository activityRepository;
  private final ActivityRelationshipRepository activityRelationshipRepository;
  private final StretchRepository stretchRepository;
  private final StretchActivityLinkRepository stretchActivityLinkRepository;
  private final LabourDesignationRepository labourDesignationRepository;
  private final ProjectLabourDeploymentRepository projectLabourDeploymentRepository;
  private final OmanRoadProjectWorkbookReader reader;
  private final ObjectMapper objectMapper;
  private final javax.sql.DataSource dataSource;

  @Override
  public void run(String... args) {
    // NOTE: deliberately no @Transactional on run(). Mirrors NHAI lines 148-158 — every
    // Spring Data JPA save() commits row-by-row, which is a precondition for the SQL
    // bundle that Agent 4 ships at @Order(170+); the bundle opens its own DataSource
    // connection and resolves natural keys (project.code='6155') via subquery.
    if (!reader.exists()) {
      log.warn("[BNK] Oman workbooks not on classpath — skipping seeder");
      return;
    }
    if (projectRepository.findByCode(PROJECT_CODE).isPresent()) {
      log.info("[BNK] project '{}' already seeded, skipping core but topping up labour deployments + activity links", PROJECT_CODE);
      // Top up: when the LabourDesignation master expands (e.g. JSON master is extended
      // or Excel-derived positions are merged in), every existing project still needs a
      // deployment row per designation. The deployment seeder is row-idempotent.
      // Also re-link activities to work_activities — needed for Capacity Utilization.
      projectRepository.findByCode(PROJECT_CODE).ifPresent(p -> {
        seedProjectLabourDeployments(p.getId());
        backfillActivityWorkActivityLinks(p.getId());
        // Manpower trades aren't in the global Resources catalogue on legacy seed data —
        // top them up here so the existing-project path matches a fresh seed.
        seedManpowerResources(p.getCalendarId());
      });
      return;
    }

    log.info("[BNK] seeding Oman Barka–Nakhal Road Project ({}) from 3 workbooks…", PROJECT_CODE);

    // 1-2: structural hierarchies (no workbook needed)
    UUID epsLeaf = seedEps();
    UUID obsLeaf = seedObs();

    // 3: project calendar
    UUID calendarId = seedCalendar();

    // 4: project record — must read project info from workbook 3
    ProjectInfo info = reader.withWorkbook(OmanRoadProjectWorkbookReader.SUPERVISOR_DBS_PATH,
        reader::readProjectInfo);
    Project project = seedProject(epsLeaf, obsLeaf, info, calendarId);
    log.info("[BNK] Project '{}' created with id={}", project.getCode(), project.getId());

    // 6-7: WBS + BOQ (read BOQ rows from workbook 3)
    List<BoqRow> boqRows = reader.withWorkbook(OmanRoadProjectWorkbookReader.SUPERVISOR_DBS_PATH,
        reader::readBoqItems);
    List<OmanRoadProjectWorkbookReader.BoqRateRow> revisedRates = reader.withWorkbook(
        OmanRoadProjectWorkbookReader.CAPACITY_UTIL_PATH,
        reader::readBoqRates);
    Map<String, UUID> wbs = seedWbs(project.getId(), boqRows);
    List<BoqItem> savedBoqs = seedBoqItems(project.getId(), wbs, boqRows, revisedRates);

    // 8: productivity norms (workbook 2)
    List<ProductivityNormRow> normRows = reader.withWorkbook(
        OmanRoadProjectWorkbookReader.CAPACITY_UTIL_PATH,
        reader::readProductivityNorms);
    seedProductivityNorms(normRows);

    // 9: equipment resources (JSON master — workbook rates are #REF!)
    seedEquipmentResourcesAndRates(calendarId);

    // 10: synthetic Gulf-market material catalogue (plan §4.7)
    seedMaterialResources(calendarId);

    // 11: manpower Role rows (resource-domain) from workbook 1
    seedManpowerResourceRoles();

    // 11b: manpower Resource rows so they appear in the global Resources catalogue
    //      (Manpower utilization trades from workbook 2 + direct-labour list from workbook 1)
    seedManpowerResources(calendarId);

    // 12: ProjectLabourDeployment — bind 44 Oman LabourDesignations to this project
    seedProjectLabourDeployments(project.getId());

    // 13-14: schedule (Activities + Relationships)
    seedActivities(project.getId(), calendarId, wbs, savedBoqs, normRows);

    // 15: chainage stretches + activity links
    seedStretches(project.getId(), savedBoqs);

    // daily data handled by OmanRoadDailyDataSeeder @Order(143)

    // 16: sanity check — log drift but don't throw (this is demo data)
    assertBoqRollupConsistent(project.getId(), boqRows);

    log.info("[BNK] done. Project '{}' core seed complete — handing off to Agents 3/4/5.",
        PROJECT_CODE);

    // All JPA writes above committed row-by-row (no outer @Transactional on run()), so the
    // SQL bundle below — which opens a fresh DataSource connection — sees this project's
    // rows via its (SELECT id FROM project.projects WHERE code = ?) natural-key subqueries.
    loadReportsDataSqlBundle("seed-data/oman-road-project/reports", PROJECT_CODE);
  }

  /**
   * Reads {@code classpath:seed-data/oman-road-project/reports/*.sql} and executes each
   * file in lexical order via {@link org.springframework.jdbc.datasource.init.ScriptUtils}.
   * Mirrors the NHAI bundle loader (line 244-264) exactly: one fresh JDBC connection per
   * file with autoCommit=true so a failure in one file is logged and the loader continues.
   * The bundle is owned by Agent 4; this method is the runtime entry-point.
   */
  private void loadReportsDataSqlBundle(String classpathDir, String projectCode) {
    org.springframework.core.io.Resource[] files;
    try {
      var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
      files = resolver.getResources("classpath:" + classpathDir + "/*.sql");
    } catch (Exception e) {
      log.error("[BNK reports] could not enumerate SQL bundle: {}", e.getMessage(), e);
      return;
    }
    if (files.length == 0) {
      log.info("[BNK reports] no SQL bundle found at {} — skipping", classpathDir);
      return;
    }
    java.util.Arrays.sort(files, java.util.Comparator.comparing(org.springframework.core.io.Resource::getFilename));
    log.info("[BNK reports] running {} demo-data SQL file(s) for '{}'", files.length, projectCode);

    int ok = 0, failed = 0;
    for (var f : files) {
      // One JDBC connection per file with autoCommit=true so each file lands independently.
      // A failure in one file is logged and the loader continues — otherwise a single bad
      // statement would prevent the remaining files from populating the report panels at all.
      try (var conn = dataSource.getConnection()) {
        boolean wasAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(true);
        try {
          log.info("[BNK reports] executing {}", f.getFilename());
          org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(conn, f);
          ok++;
        } finally {
          conn.setAutoCommit(wasAutoCommit);
        }
      } catch (Exception e) {
        failed++;
        log.error("[BNK reports] {} failed: {}", f.getFilename(), e.getMessage());
      }
    }
    log.info("[BNK reports] bundle finished — {} succeeded, {} failed", ok, failed);
  }

  // ─────────────────────────── EPS ───────────────────────────
  /**
   * 4-level EPS hierarchy: OHA → OHA-MUS-BAT → OHA-MUS-BAT-CORR-BNK → OHA-PRJ-6155.
   * Returns the leaf id for the project record.
   */
  private UUID seedEps() {
    EpsNode oha = saveEps("OHA",
        "Oman Highways Authority (Ministry of Transport, Communications & IT)",
        null, 0);
    EpsNode region = saveEps("OHA-MUS-BAT", "Muscat–Batinah Region", oha.getId(), 0);
    EpsNode corridor = saveEps("OHA-MUS-BAT-CORR-BNK",
        "Barka–Nakhal Corridor", region.getId(), 0);
    EpsNode project = saveEps("OHA-PRJ-" + PROJECT_CODE,
        "Project " + PROJECT_CODE + " — Barka–Nakhal Dualization", corridor.getId(), 0);
    log.info("[BNK] Seeded 4 EPS nodes");
    return project.getId();
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
  /**
   * 4-level OBS with 9 nodes (per plan §4.2). The project's OBS leaf is BNK-PM-TEAM,
   * and the 4 site supervisors hang off it as named leaves.
   */
  private UUID seedObs() {
    ObsNode motc = saveObs("MOTC",
        "Ministry of Transport, Communications & IT", null, 0);
    ObsNode rdp = saveObs("MOTC-RDP",
        "Roads & Land Transport Directorate", motc.getId(), 0);
    ObsNode consEng = saveObs("OHA-CONS-ENG",
        "Engineer / Consultant (Independent)", rdp.getId(), 0);
    ObsNode contractor = saveObs("BNK-CONTRACTOR",
        "Main Works Contractor — Barka–Nakhal", rdp.getId(), 1);
    ObsNode pmTeam = saveObs("BNK-PM-TEAM",
        "Project Team — PM Mohsin Ahmad", contractor.getId(), 0);
    ObsNode supTswamy = saveObs("BNK-SUP-TSWAMY",     "Site Supervisor — T. Swamy (Section A)",     pmTeam.getId(), 0);
    saveObs("BNK-SE-TSWAMY",     "Site Engineer — T. Swamy Team",     supTswamy.getId(), 0);
    saveObs("BNK-CM-TSWAMY",     "Construction Manager — T. Swamy Team",     supTswamy.getId(), 1);

    ObsNode supNagarajan = saveObs("BNK-SUP-NAGARAJAN",  "Site Supervisor — Nagarajan (Section B)",    pmTeam.getId(), 1);
    saveObs("BNK-SE-NAGARAJAN",  "Site Engineer — Nagarajan Team",  supNagarajan.getId(), 0);
    saveObs("BNK-CM-NAGARAJAN",  "Construction Manager — Nagarajan Team",  supNagarajan.getId(), 1);

    ObsNode supAksingh = saveObs("BNK-SUP-AKSINGH",    "Site Supervisor — A.K. Singh (Section C)",   pmTeam.getId(), 2);
    saveObs("BNK-SE-AKSINGH",    "Site Engineer — A.K. Singh Team",    supAksingh.getId(), 0);
    saveObs("BNK-CM-AKSINGH",    "Construction Manager — A.K. Singh Team",    supAksingh.getId(), 1);

    ObsNode supAnbazhagan = saveObs("BNK-SUP-ANBAZHAGAN", "Site Supervisor — Anbazhagan (Section D)",   pmTeam.getId(), 3);
    saveObs("BNK-SE-ANBAZHAGAN", "Site Engineer — Anbazhagan Team", supAnbazhagan.getId(), 0);
    saveObs("BNK-CM-ANBAZHAGAN", "Construction Manager — Anbazhagan Team", supAnbazhagan.getId(), 1);

    ObsNode supManoj = saveObs("BNK-SUP-MANOJ",  "Site Supervisor — Manoj (Section E)",    pmTeam.getId(), 4);
    saveObs("BNK-SE-MANOJ",  "Site Engineer — Manoj Team",  supManoj.getId(), 0);
    saveObs("BNK-CM-MANOJ",  "Construction Manager — Manoj Team",  supManoj.getId(), 1);

    ObsNode supLiju = saveObs("BNK-SUP-LIJU",   "Site Supervisor — Liju (Section F)",     pmTeam.getId(), 5);
    saveObs("BNK-SE-LIJU",   "Site Engineer — Liju Team",   supLiju.getId(), 0);
    saveObs("BNK-CM-LIJU",   "Construction Manager — Liju Team",   supLiju.getId(), 1);

    ObsNode supAnish = saveObs("BNK-SUP-ANISH",  "Site Supervisor — Anish (Section G)",    pmTeam.getId(), 6);
    saveObs("BNK-SE-ANISH",  "Site Engineer — Anish Team",  supAnish.getId(), 0);
    saveObs("BNK-CM-ANISH",  "Construction Manager — Anish Team",  supAnish.getId(), 1);

    ObsNode supAnand = saveObs("BNK-SUP-ANAND",  "Site Supervisor — Anand (Section H)",    pmTeam.getId(), 7);
    saveObs("BNK-SE-ANAND",  "Site Engineer — Anand Team",  supAnand.getId(), 0);
    saveObs("BNK-CM-ANAND",  "Construction Manager — Anand Team",  supAnand.getId(), 1);

    ObsNode supMohsin = saveObs("BNK-SUP-MOHSIN", "Site Supervisor — Mohsin Mohamed (Section I)", pmTeam.getId(), 8);
    saveObs("BNK-SE-MOHSIN", "Site Engineer — Mohsin Mohamed Team", supMohsin.getId(), 0);
    saveObs("BNK-CM-MOHSIN", "Construction Manager — Mohsin Mohamed Team", supMohsin.getId(), 1);

    // Suppress unused-warning when consEng not yet referenced — it's in the tree.
    if (consEng == null) throw new IllegalStateException("OBS save returned null");
    log.info("[BNK] Seeded 25 OBS nodes");
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
  /**
   * Oman 5-day construction calendar — Sun–Thu working, Fri–Sat off. 8h/day
   * (08:00–12:00 + 13:00–17:00) per plan §4.3.
   */
  private UUID seedCalendar() {
    Calendar cal = Calendar.builder()
        .code("OMAN-5day")
        .name("Oman 5-day Construction Calendar (Sun–Thu)")
        .description("Sun–Thu WORKING 8 hrs/day (08:00–12:00 + 13:00–17:00); Fri/Sat NON_WORKING.")
        .calendarType(CalendarType.GLOBAL)
        .isDefault(false)
        .standardWorkHoursPerDay(8.0)
        .standardWorkDaysPerWeek(5)
        .build();
    Calendar saved = calendarRepository.save(cal);
    for (DayOfWeek day : DayOfWeek.values()) {
      // In Oman the standard work week is Sun–Thu. Friday & Saturday are the weekend.
      boolean working = (day != DayOfWeek.FRIDAY && day != DayOfWeek.SATURDAY);
      CalendarWorkWeek ww = CalendarWorkWeek.builder()
          .calendarId(saved.getId())
          .dayOfWeek(day)
          .dayType(working ? DayType.WORKING : DayType.NON_WORKING)
          .totalWorkHours(working ? 8.0 : 0.0)
          .startTime1(working ? LocalTime.of(8, 0) : null)
          .endTime1(working ? LocalTime.of(12, 0) : null)
          .startTime2(working ? LocalTime.of(13, 0) : null)
          .endTime2(working ? LocalTime.of(17, 0) : null)
          .build();
      calendarWorkWeekRepository.save(ww);
    }
    log.info("[BNK] Seeded calendar OMAN-5day");
    return saved.getId();
  }

  // ────────────────────────── Project ────────────────────────
  private Project seedProject(UUID epsLeafId, UUID obsLeafId, ProjectInfo info, UUID calendarId) {
    Project p = new Project();
    p.setCode(PROJECT_CODE);
    p.setName(nullSafe(info.projectName(), "Dualization of Barka–Nakhal Road"));
    p.setDescription("Dualization (twin-carriageway upgrade) of the Barka–Nakhal Road in "
        + "Muscat–Batinah, Oman. ~41 km between Barka Junction (Km 0+000) and Nakhal "
        + "Roundabout (Km 41+000). Source: 3-workbook DPR/Capacity/DBS dataset.");
    p.setEpsNodeId(epsLeafId);
    p.setObsNodeId(obsLeafId);
    p.setPlannedStartDate(DEFAULT_START);
    p.setPlannedFinishDate(DEFAULT_FINISH);
    p.setDataDate(DEFAULT_DATA_DATE);
    // ProjectStatus enum doesn't have EXECUTION — use ACTIVE (closest match).
    p.setStatus(ProjectStatus.ACTIVE);
    p.setPriority(1);
    p.setCategory("HIGHWAY");
    p.setMorthCode("MOTC-BNK-" + PROJECT_CODE);
    p.setFromChainageM(CHAINAGE_START_M);
    p.setToChainageM(CHAINAGE_END_M);
    p.setFromLocation("Barka Junction (Km 0+000)");
    p.setToLocation("Nakhal Roundabout (Km 41+000)");
    p.setTotalLengthKm(BigDecimal.valueOf(
        (CHAINAGE_END_M - CHAINAGE_START_M) / 1000.0).setScale(3, RoundingMode.HALF_UP));
    p.setCalendarId(calendarId);
    // Currency — Project entity has budgetCurrency (default INR). Oman is OMR.
    p.setBudgetCurrency("OMR");
    // Deterministic project BAC. The whole UI/API stores budgets in the currency's
    // major-scale unit (crores for INR, millions for OMR), so we compute in millions
    // here. Reference: ~1.5M OMR/km for a dual-carriageway upgrade (MOTC benchmark).
    BigDecimal lengthKm = BigDecimal.valueOf(
        (CHAINAGE_END_M - CHAINAGE_START_M) / 1000.0);
    BigDecimal omrMillionsPerKm = new BigDecimal("1.5");
    BigDecimal bac = lengthKm.multiply(omrMillionsPerKm).setScale(2, RoundingMode.HALF_UP);
    p.setOriginalBudget(bac);
    p.setCurrentBudget(bac);
    p.setBudgetUpdatedAt(java.time.Instant.now());
    return projectRepository.save(p);
  }

  // ────────────────────────── WBS ────────────────────────────
  /**
   * WBS hierarchy with up to 3 levels:
   *   ROOT (L1) → 9 L2 sections (loaded from oman-road-boq-section-prefixes.json)
   *              → L3 sub-packages derived from second segment of BOQ codes.
   * budgetCrores rolls up BOQ amounts in MILLIONS OMR (so the existing field stores
   * OMR/1,000,000). Agent 4's SQL bundle (01-wbs-budget.sql) overwrites the values
   * with EVM-aligned numbers; we plant a reasonable rollup here so the tab renders
   * even if the SQL bundle hasn't run yet.
   */
  private Map<String, UUID> seedWbs(UUID projectId, List<BoqRow> boqRows) {
    // Root
    WbsNode root = new WbsNode();
    root.setCode("WBS-ROOT");
    root.setName("Barka–Nakhal Dualization — Root");
    root.setParentId(null);
    root.setProjectId(projectId);
    root.setSortOrder(0);
    root.setWbsLevel(1);
    root.setWbsType(WbsType.PROGRAMME);
    root.setPhase(WbsPhase.CONSTRUCTION);
    root.setWbsStatus(WbsStatus.ACTIVE);
    root.setChainageFromM(CHAINAGE_START_M);
    root.setChainageToM(CHAINAGE_END_M);
    UUID rootId = wbsNodeRepository.save(root).getId();

    // L2 = section prefixes from JSON
    List<Map<String, Object>> sectionPrefixes = readJsonList("oman-road-boq-section-prefixes.json");
    // Sum BOQ amounts (in OMR) by L1 prefix (first character of code, e.g. "1" of "1.3.5(i)a").
    Map<String, BigDecimal> amountByPrefix = sumBoqAmountsByL1Prefix(boqRows);

    Map<String, UUID> wbsByKey = new LinkedHashMap<>();
    int order = 0;
    for (Map<String, Object> sec : sectionPrefixes) {
      String prefix = (String) sec.get("prefix");
      String wbsCode = (String) sec.get("wbsCode");
      String name = (String) sec.get("name");
      String phase = (String) sec.get("phase");
      WbsPhase wbsPhase = mapPhase(phase);
      BigDecimal omr = amountByPrefix.getOrDefault(prefix, BigDecimal.ZERO);
      // Convert OMR → millions of OMR (existing column is "budgetCrores" but unit is repurposed).
      BigDecimal millions = omr.divide(new BigDecimal("1000000"), 4, RoundingMode.HALF_UP);

      WbsNode n = new WbsNode();
      n.setCode(wbsCode);
      n.setName(name);
      n.setParentId(rootId);
      n.setProjectId(projectId);
      n.setSortOrder(order++);
      n.setWbsLevel(2);
      n.setWbsType(WbsType.PACKAGE);
      n.setPhase(wbsPhase);
      n.setWbsStatus(WbsStatus.ACTIVE);
      n.setBudgetCrores(millions.setScale(2, RoundingMode.HALF_UP));
      n.setChainageFromM(CHAINAGE_START_M);
      n.setChainageToM(CHAINAGE_END_M);
      WbsNode saved = wbsNodeRepository.save(n);
      wbsByKey.put(prefix, saved.getId());
    }

    // L3 — derive from the second segment of each BOQ code (e.g. "1.3.5(i)a" → L3 "1.3").
    Map<String, String> l3NameByKey = new LinkedHashMap<>();
    for (BoqRow b : boqRows) {
      if (b.code() == null || b.code().isBlank()) continue;
      String[] segs = b.code().split("\\.");
      if (segs.length < 2) continue;
      String l3Key = segs[0] + "." + stripParens(segs[1]);
      l3NameByKey.putIfAbsent(l3Key, summariseGroupName(b.description()));
    }
    Map<String, BigDecimal> amountByL3 = sumBoqAmountsByL3(boqRows);
    int l3Sort = 0;
    for (Map.Entry<String, String> e : l3NameByKey.entrySet()) {
      String l3Key = e.getKey();
      String l1Key = l3Key.split("\\.")[0];
      UUID parent = wbsByKey.get(l1Key);
      if (parent == null) continue;
      BigDecimal omr = amountByL3.getOrDefault(l3Key, BigDecimal.ZERO);
      BigDecimal millions = omr.divide(new BigDecimal("1000000"), 4, RoundingMode.HALF_UP);
      WbsNode n = new WbsNode();
      n.setCode("WBS-" + l3Key);
      n.setName(e.getValue());
      n.setParentId(parent);
      n.setProjectId(projectId);
      n.setSortOrder(l3Sort++);
      n.setWbsLevel(3);
      n.setWbsType(WbsType.WORK_PACKAGE);
      n.setPhase(WbsPhase.CONSTRUCTION);
      n.setWbsStatus(WbsStatus.ACTIVE);
      n.setBudgetCrores(millions.setScale(2, RoundingMode.HALF_UP));
      n.setChainageFromM(CHAINAGE_START_M);
      n.setChainageToM(CHAINAGE_END_M);
      WbsNode saved = wbsNodeRepository.save(n);
      // Store under L3 key so BOQ items can also pick this up if they want sub-package linkage.
      wbsByKey.put(l3Key, saved.getId());
    }

    // Total nodes saved = 1 root + L2 + L3
    long total = 1L + sectionPrefixes.size() + l3NameByKey.size();
    log.info("[BNK] Seeded {} WBS nodes", total);
    return wbsByKey;
  }

  /** Map JSON phase string to {@link WbsPhase} (with safe fallbacks). */
  private WbsPhase mapPhase(String phase) {
    if (phase == null) return WbsPhase.CONSTRUCTION;
    String lc = phase.toUpperCase(Locale.ROOT);
    return switch (lc) {
      case "MOBILIZATION", "MOBILISATION" -> WbsPhase.MOBILISATION;
      case "PLANNING" -> WbsPhase.PLANNING;
      case "TENDER" -> WbsPhase.TENDER;
      case "PROGRAMME" -> WbsPhase.PROGRAMME;
      default -> WbsPhase.CONSTRUCTION;
    };
  }

  private Map<String, BigDecimal> sumBoqAmountsByL1Prefix(List<BoqRow> rows) {
    Map<String, BigDecimal> out = new HashMap<>();
    int idx = 0;
    for (BoqRow b : rows) {
      String prefix = l1Prefix(b.code());
      if (prefix == null) continue;
      BigDecimal amount = computeBoqAmount(b, idx++);
      out.merge(prefix, amount, BigDecimal::add);
    }
    return out;
  }

  private Map<String, BigDecimal> sumBoqAmountsByL3(List<BoqRow> rows) {
    Map<String, BigDecimal> out = new HashMap<>();
    int idx = 0;
    for (BoqRow b : rows) {
      if (b.code() == null) continue;
      String[] segs = b.code().split("\\.");
      if (segs.length < 2) continue;
      String l3Key = segs[0] + "." + stripParens(segs[1]);
      BigDecimal amount = computeBoqAmount(b, idx++);
      out.merge(l3Key, amount, BigDecimal::add);
    }
    return out;
  }

  /** First character of code, treated as the L1 section prefix ("1".."9"). */
  private String l1Prefix(String code) {
    if (code == null || code.isBlank()) return null;
    char c = code.charAt(0);
    return Character.isDigit(c) ? String.valueOf(c) : null;
  }

  /** Strip parens / non-digit suffixes ("3(a)" → "3"). */
  private String stripParens(String seg) {
    StringBuilder sb = new StringBuilder();
    for (char c : seg.toCharArray()) {
      if (Character.isDigit(c)) sb.append(c);
      else break;
    }
    return sb.length() == 0 ? seg : sb.toString();
  }

  /**
   * Compute the boqAmount in OMR for rolling up to WBS. Uses literal qty × rate where
   * present; falls back to a deterministic synthetic amount when either is null.
   */
  private BigDecimal computeBoqAmount(BoqRow b, int idx) {
    if (b.planAmount() != null && b.planAmount().signum() > 0) {
      return b.planAmount();
    }
    if (b.planQty() != null && b.rate() != null) {
      return b.planQty().multiply(b.rate());
    }
    // Fallback synthetic when both qty and rate are missing in the workbook.
    // Stable bucket within 5,000 – 50,000 OMR per item so totals reproduce. No RNG.
    String key = b.code() != null ? b.code() : ("idx-" + idx);
    long bucket = (Math.abs((long) key.hashCode()) % 46L) * 1_000L; // 0..45,000 step 1k
    return new BigDecimal(5_000L + bucket).setScale(3, RoundingMode.HALF_UP);
  }

  private String summariseGroupName(String description) {
    if (description == null || description.isBlank()) return "Sub-package";
    String head = description.split("[–\\-(,]", 2)[0].trim();
    if (head.length() > 80) head = head.substring(0, 80);
    return head.isEmpty() ? description.trim() : head;
  }

  // ────────────────────────── BOQ Items ──────────────────────
  /**
   * One BoqItem per workbook row. Codes like "1.3.5(i)a" are preserved verbatim.
   * Where rate / qty / amounts are null (workbook has lots of #REF!), we synthesise
   * deterministic values from the RNG seeded with the project code + item index.
   * After populating fields we always call {@link BoqCalculator#recompute(BoqItem)}.
   */
  private List<BoqItem> seedBoqItems(UUID projectId, Map<String, UUID> wbs, List<BoqRow> rows,
                                     List<OmanRoadProjectWorkbookReader.BoqRateRow> revisedRates) {
    List<BoqItem> items = new ArrayList<>();
    int idx = 0;
    for (BoqRow r : rows) {
      String code = r.code();
      if (code == null) { idx++; continue; }
      // BoqItem.itemNo column is length=20; codes like "1.3.5(i)a" fit, but truncate defensively.
      String itemNo = code.length() > 20 ? code.substring(0, 20) : code;
      String prefix = l1Prefix(code);
      // Prefer L3 WBS link if we have one (e.g. "1.3"); else fall back to L1 ("1").
      UUID wbsNodeId = null;
      if (code.contains(".")) {
        String[] segs = code.split("\\.");
        if (segs.length >= 2) {
          String l3Key = segs[0] + "." + stripParens(segs[1]);
          wbsNodeId = wbs.get(l3Key);
        }
      }
      if (wbsNodeId == null && prefix != null) {
        wbsNodeId = wbs.get(prefix);
      }

      // Fallback values when the workbook does not supply rate/qty/achieved — derived
      // deterministically from a stable hash of itemNo. No RNG: re-running the seeder must
      // produce identical numbers so EVM/cost reports are reproducible.
      int hash = Math.abs(itemNo.hashCode());
      BigDecimal rate = r.rate();
      if (rate == null || rate.signum() == 0) {
        // Try revised rates from File 2 (Capacity Utilization workbook) by code prefix match.
        if (revisedRates != null) {
          for (OmanRoadProjectWorkbookReader.BoqRateRow rr : revisedRates) {
            if (rr.code() != null && !rr.code().isBlank()
                && rr.revisedRate() != null && rr.revisedRate().signum() > 0
                && itemNo.startsWith(rr.code())) {
              rate = rr.revisedRate();
              break;
            }
          }
        }
      }
      if (rate == null || rate.signum() == 0) {
        // OMR/unit fallback by stable category bucket — fixed reference rates, no noise.
        int bucket = hash % 5;
        double base = switch (bucket) {
          case 0 -> 5.0;
          case 1 -> 25.0;
          case 2 -> 60.0;
          case 3 -> 120.0;
          default -> 0.5; // very cheap (e.g. per-kg materials)
        };
        rate = BigDecimal.valueOf(base).setScale(3, RoundingMode.HALF_UP);
      }
      BigDecimal qty = r.planQty();
      if (qty == null || qty.signum() == 0) {
        // Quantity fallback: stable bucket within 50 – 5,000 unit range.
        long qtyVal = 50L + (hash % 4_951L);
        qty = BigDecimal.valueOf(qtyVal).setScale(3, RoundingMode.HALF_UP);
      }
      BigDecimal qtyExecuted = r.achievedQty();
      if (qtyExecuted == null || qtyExecuted.signum() == 0) {
        // Execution % fallback: stable [0%, 70%] derived from itemNo hash.
        double frac = (hash % 71) / 100.0;
        qtyExecuted = qty.multiply(BigDecimal.valueOf(frac))
            .setScale(3, RoundingMode.HALF_UP);
      }
      // Budgeted rate = literal rate; actual rate = literal × 1.03 (3% deterministic overrun).
      BigDecimal budgetedRate = rate;
      BigDecimal actualRate = rate.multiply(new BigDecimal("1.03"))
          .setScale(4, RoundingMode.HALF_UP);

      String unit = r.unit();
      if (unit == null || unit.isBlank()) unit = "Nr.";
      if (unit.length() > 20) unit = unit.substring(0, 20);

      BoqItem b = BoqItem.builder()
          .projectId(projectId)
          .itemNo(itemNo)
          .description(truncate(nullSafe(r.description(), "BOQ item " + itemNo), 500))
          .unit(unit)
          .wbsNodeId(wbsNodeId)
          .boqQty(qty)
          .boqRate(rate)
          .budgetedRate(budgetedRate)
          .qtyExecutedToDate(qtyExecuted)
          .actualRate(actualRate)
          .build();
      BoqCalculator.recompute(b);
      items.add(b);
      idx++;
    }
    List<BoqItem> saved = boqItemRepository.saveAll(items);
    log.info("[BNK] Seeded {} BOQ items", saved.size());
    return saved;
  }

  // ────────────────────── Productivity Norms ─────────────────
  /**
   * Each row from the workbook → one ProductivityNorm. Norm type heuristic: the
   * "Plant utilization" sheet flagged its rows into the EQUIPMENT bucket based on
   * the equipmentOrLabour name; "Manpower utilization" rows are MANPOWER. We can't
   * tell the source sheet from the row record so we infer from common equipment
   * keywords.
   */
  private void seedProductivityNorms(List<ProductivityNormRow> rows) {
    List<ProductivityNorm> all = new ArrayList<>();
    for (ProductivityNormRow r : rows) {
      String activityName = r.activity();
      if (activityName == null || activityName.isBlank()) continue;
      String unit = nullSafe(r.unit(), "Nr.");
      if (unit.length() > 20) unit = unit.substring(0, 20);
      WorkActivity wa = workActivityService.findOrCreateByName(activityName, unit);
      ProductivityNormType normType = inferNormType(r.equipmentOrLabour());

      ProductivityNorm norm = ProductivityNorm.builder()
          .normType(normType)
          .workActivity(wa)
          .activityName(wa.getName())
          .unit(unit)
          .outputPerDay(r.outputPerDay())
          .outputPerHour(r.outputPerDay() != null
              ? r.outputPerDay().divide(BigDecimal.valueOf(8), 4, RoundingMode.HALF_UP)
              : null)
          .crewSize(normType == ProductivityNormType.MANPOWER ? 4 : null)
          .outputPerManPerDay(normType == ProductivityNormType.MANPOWER && r.outputPerDay() != null
              ? r.outputPerDay().divide(BigDecimal.valueOf(4), 4, RoundingMode.HALF_UP)
              : null)
          .workingHoursPerDay(8.0)
          .equipmentSpec(normType == ProductivityNormType.EQUIPMENT ? r.equipmentOrLabour() : null)
          .remarks("Source: Capacity Utilization workbook (Plant/Manpower utilization sheets)")
          .build();
      all.add(norm);
    }
    productivityNormRepository.saveAll(all);
    log.info("[BNK] Seeded {} productivity norms", all.size());
  }

  /** Infer norm type from the equipment-or-labour name (default EQUIPMENT). */
  private ProductivityNormType inferNormType(String label) {
    if (label == null) return ProductivityNormType.EQUIPMENT;
    String lc = label.toLowerCase(Locale.ROOT);
    if (lc.contains("mason") || lc.contains("carpenter") || lc.contains("helper")
        || lc.contains("steel fixer") || lc.contains("foreman") || lc.contains("labour")
        || lc.contains("worker") || lc.contains("electrician") || lc.contains("plumber")) {
      return ProductivityNormType.MANPOWER;
    }
    return ProductivityNormType.EQUIPMENT;
  }

  // ────────────────────── Equipment Resources ─────────────────
  /**
   * 34 equipment items loaded from {@code oman-road-equipment-master.json} (the
   * workbook rates are #REF!). One {@link Resource} + 2 {@link ResourceRate} rows
   * per item (BUDGETED at literal rate, ACTUAL at literal × 1.05).
   */
  private void seedEquipmentResourcesAndRates(UUID calendarId) {
    List<Map<String, Object>> equipment = readJsonList("oman-road-equipment-master.json");
    ResourceType eqType = resourceFactory.requireType("EQUIPMENT");
    int resCount = 0, rateCount = 0;
    for (Map<String, Object> e : equipment) {
      String code = "BNK-EQ-" + slug((String) e.get("code"));
      String name = (String) e.get("name");
      String unit = (String) e.getOrDefault("unit", "Day");
      BigDecimal rate = new BigDecimal(e.get("ratePerDayOmr").toString());
      ResourceRole role = resourceFactory.ensureRole(equipmentRoleCodeFor(name), "EQUIPMENT");

      Resource res = Resource.builder()
          .code(code)
          .name(name)
          .resourceType(eqType)
          .role(role)
          .unit(resourceUnitCodeFor(unit))
          .calendarId(calendarId)
          .status(ResourceStatus.ACTIVE)
          .availability(BigDecimal.valueOf(100))
          .costPerUnit(rate)
          .build();
      Resource saved = resourceRepository.save(res);
      resourceEquipmentDetailsRepository.save(ResourceEquipmentDetails.builder()
          .resourceId(saved.getId())
          .build());
      resCount++;
      resourceRateRepository.save(buildRate(saved.getId(), "BUDGETED", rate, DEFAULT_START));
      resourceRateRepository.save(buildRate(saved.getId(), "ACTUAL",
          rate.multiply(new BigDecimal("1.05")).setScale(3, RoundingMode.HALF_UP),
          DEFAULT_START));
      rateCount += 2;
    }
    log.info("[BNK] Seeded {} equipment resources + {} rates", resCount, rateCount);
  }

  // ────────────────────── Material Resources ─────────────────
  /**
   * 23 synthetic Gulf-market materials per plan §4.7. Workbook has nearly no material
   * data (only "Royalty Charges" appears literally), so we hand-curate a credible list
   * with OMR rates that drive the Materials / Material Catalogue tabs.
   */
  private void seedMaterialResources(UUID calendarId) {
    Object[][] materials = {
        {"BNK-MT-CEMENT-OPC43",  "Cement OPC 43",                   "MT",     "38.000"},
        {"BNK-MT-AGGR-20",       "Aggregate 20mm",                  "MT",     "4.500"},
        {"BNK-MT-AGGR-10",       "Aggregate 10mm",                  "MT",     "5.000"},
        {"BNK-MT-SAND-WASHED",   "Sand Washed",                     "MT",     "3.200"},
        {"BNK-MT-GSB",           "Granular Sub-Base material",      "Cum",    "6.000"},
        {"BNK-MT-WMM",           "Wet Mix Macadam",                 "Cum",    "7.500"},
        {"BNK-MT-BIT-VG30",      "Bitumen VG-30",                   "MT",     "250.000"},
        {"BNK-MT-BIT-EMUL",      "Bitumen Emulsion",                "Kg",     "0.400"},
        {"BNK-MT-PRIME",         "Prime Coat (MC-30)",              "Kg",     "0.600"},
        {"BNK-MT-DBM",           "DBM Mix",                         "MT",     "32.000"},
        {"BNK-MT-BC",            "BC Mix",                          "MT",     "40.000"},
        {"BNK-MT-STEEL-FE500",   "Steel Reinforcement Fe500",       "MT",     "480.000"},
        {"BNK-MT-CONC-C30",      "Concrete C30/20",                 "Cum",    "55.000"},
        {"BNK-MT-CONC-C45",      "Concrete C45/20",                 "Cum",    "75.000"},
        {"BNK-MT-HDPE-PIPE",     "HDPE Storm Sewer Pipe",           "RM",     "18.000"},
        {"BNK-MT-UPVC-100",      "UPVC Pipe 100mm",                 "RM",     "6.500"},
        {"BNK-MT-DUCT-100",      "Service Duct 100mm",              "RM",     "8.500"},
        {"BNK-MT-DUCT-150",      "Service Duct 150mm",              "RM",     "12.000"},
        {"BNK-MT-INTERLOCK",     "Interlocking Tiles",              "Sqm",    "7.000"},
        {"BNK-MT-KERB",          "Kerb Stones",                     "RM",     "4.000"},
        {"BNK-MT-GUARDRAIL",     "Steel Safety Barrier",            "RM",     "35.000"},
        {"BNK-MT-SIGN",          "Project Signboards (Aluminum)",   "Each",   "120.000"},
        {"BNK-MT-ROYALTY",       "Royalty Charges",                 "Cum",    "1.500"}
    };
    int resCount = 0, rateCount = 0;
    ResourceType matType = resourceFactory.requireType("MATERIAL");
    for (Object[] m : materials) {
      String code = (String) m[0];
      String name = (String) m[1];
      String unit = (String) m[2];
      BigDecimal rate = new BigDecimal((String) m[3]);
      ResourceRole role = resourceFactory.ensureRole(materialRoleCodeFor(name), "MATERIAL");
      Resource res = Resource.builder()
          .code(code)
          .name(name)
          .resourceType(matType)
          .role(role)
          .unit(resourceUnitCodeFor(unit))
          .calendarId(calendarId)
          .status(ResourceStatus.ACTIVE)
          .availability(BigDecimal.valueOf(100))
          .costPerUnit(rate)
          .build();
      Resource saved = resourceRepository.save(res);
      resourceMaterialDetailsRepository.save(ResourceMaterialDetails.builder()
          .resourceId(saved.getId())
          .baseUnit(resourceUnitCodeFor(unit))
          .build());
      resCount++;
      resourceRateRepository.save(buildRate(saved.getId(), "BUDGETED", rate, DEFAULT_START));
      resourceRateRepository.save(buildRate(saved.getId(), "ACTUAL",
          rate.multiply(new BigDecimal("1.05")).setScale(3, RoundingMode.HALF_UP),
          DEFAULT_START));
      rateCount += 2;
    }
    log.info("[BNK] Seeded {} material resources + {} rates", resCount, rateCount);
  }

  private String equipmentRoleCodeFor(String name) {
    if (name == null) return "OTHER";
    String n = name.toLowerCase(Locale.ROOT);
    if (n.contains("excavator") || n.contains("dozer") || n.contains("grader")
        || n.contains("loader") || n.contains("tipper") || n.contains("back hoe")) {
      return "EARTH_MOVING";
    }
    if (n.contains("crane") || n.contains("manlift") || n.contains("hiab")
        || n.contains("cargo")) {
      return "CRANES_LIFTING";
    }
    if (n.contains("paver") || n.contains("roller") || n.contains("bitumen distributor")) {
      return "PAVING_EQUIPMENT";
    }
    if (n.contains("trailer") || n.contains("low bed") || n.contains("tanker")) {
      return "TRANSPORT_VEHICLES";
    }
    if (n.contains("vibrator") || n.contains("compactor")) {
      return "CONCRETE_EQUIPMENT";
    }
    return "OTHER";
  }

  private String materialRoleCodeFor(String name) {
    if (name == null) return "OTHER";
    String n = name.toLowerCase(Locale.ROOT);
    if (n.contains("cement")) return "CEMENT";
    if (n.contains("steel")) return "STEEL_REBAR";
    if (n.contains("aggregate") || n.contains("sand") || n.contains("gsb")
        || n.contains("wmm")) return "AGGREGATE";
    if (n.contains("bitumen") || n.contains("prime") || n.contains("emulsion")
        || n.contains("dbm") || n.contains("bc mix")) return "BITUMEN";
    if (n.contains("concrete")) return "READY_MIX_CONCRETE";
    return "OTHER";
  }

  private String resourceUnitCodeFor(String label) {
    if (label == null) return "PER_DAY";
    return switch (label.toLowerCase(Locale.ROOT)) {
      case "cum" -> "CU_M";
      case "mt", "bag" -> "MT";
      case "rm", "rmt", "lin.m" -> "RMT";
      case "each", "nr.", "nr", "no", "nos" -> "NOS";
      case "kg" -> "KG";
      case "litre", "l", "liter" -> "LITRE";
      default -> "PER_DAY";
    };
  }

  private ResourceRate buildRate(UUID resourceId, String rateType,
                                 BigDecimal price, LocalDate effective) {
    ResourceRate rate = new ResourceRate();
    rate.setResourceId(resourceId);
    rate.setRateType(rateType);
    rate.setPricePerUnit(price != null ? price : BigDecimal.ZERO);
    rate.setEffectiveDate(effective);
    return rate;
  }

  // ───────────────── Manpower Resources (catalogue rows) ─────────────────
  /**
   * Creates a {@code Resource} row of type {@code LABOR} for every distinct manpower trade
   * surfaced by the Oman workbooks, so the global Resources catalogue exposes them
   * (otherwise the Resources page shows Manpower=0).
   *
   * <p>Two sources are merged and deduped by name:
   * <ul>
   *   <li><b>Workbook 2 / Manpower utilization sheet</b> — the 4 productivity-norm trades
   *       that the Capacity Utilization report tracks (Mason, Carpenter, Steel Fixer,
   *       Helper). Cost-per-unit reads from the productivity norm if present.</li>
   *   <li><b>Workbook 1 / Resource sheet (direct-labour right column)</b> — the 39 site
   *       trades that are deployed daily (Welder, Plumber, Electrician, Operator,
   *       Painter, Rigger, Scaffolder, etc.). These don't all have norms; they get
   *       the deterministic OMR/day rate from {@link #manpowerDailyRateOmr}.</li>
   * </ul>
   *
   * <p>Idempotent — skipped per-row by code.
   */
  private void seedManpowerResources(UUID calendarId) {
    ResourceType laborType = resourceFactory.requireType("LABOR");

    // 1. Trades with explicit productivity norms (Manpower utilization sheet).
    List<ProductivityNormRow> manpowerNorms = reader.withWorkbook(
        OmanRoadProjectWorkbookReader.CAPACITY_UTIL_PATH,
        wb -> {
          List<ProductivityNormRow> all = reader.readProductivityNorms(wb);
          // Drop equipment rows — they were already seeded as EQUIPMENT resources.
          // Manpower utilization sheet header trades are: Mason, Carpenter, Steel Fixer, Helper.
          java.util.Set<String> manpowerSet = java.util.Set.of(
              "mason", "carpenter", "steel fixer", "helper");
          return all.stream()
              .filter(r -> r.equipmentOrLabour() != null
                  && manpowerSet.contains(r.equipmentOrLabour().toLowerCase(Locale.ROOT).trim()))
              .toList();
        });

    java.util.Map<String, ProductivityNormRow> normByTrade = new java.util.LinkedHashMap<>();
    for (ProductivityNormRow row : manpowerNorms) {
      String trade = row.equipmentOrLabour();
      if (trade == null) continue;
      // Keep first norm row per trade (representative for the resource definition).
      normByTrade.putIfAbsent(trade.trim(), row);
    }

    // 2. All direct labour positions from workbook 1.
    List<DirectLabourRow> directRows = reader.withWorkbook(
        OmanRoadProjectWorkbookReader.DPR_INTERNAL_PATH, reader::readDirectLabour);

    int resCount = 0, rateCount = 0;
    java.util.Set<String> seenCodes = new java.util.HashSet<>();
    int sortOrder = 0;

    // Pass 1: norm-backed manpower trades — these are the Capacity Utilization headers.
    for (java.util.Map.Entry<String, ProductivityNormRow> e : normByTrade.entrySet()) {
      String trade = e.getKey();
      ProductivityNormRow row = e.getValue();
      String code = "BNK-MP-" + slug(trade);
      if (code.length() > 50) code = code.substring(0, 50);
      if (!seenCodes.add(code)) continue;
      if (resourceRepository.findByCode(code).isPresent()) continue;
      String unit = row.unit() != null && !row.unit().isBlank() ? row.unit() : "Day";
      BigDecimal rate = manpowerDailyRateOmr(trade);
      ResourceRole role = resourceFactory.ensureRole("ROLE-" + slug(trade), "LABOR");
      Resource res = Resource.builder()
          .code(code)
          .name(trade)
          .resourceType(laborType)
          .role(role)
          .unit(resourceUnitCodeFor(unit))
          .calendarId(calendarId)
          .status(ResourceStatus.ACTIVE)
          .availability(BigDecimal.valueOf(100))
          .costPerUnit(rate)
          .sortOrder(sortOrder++)
          .build();
      Resource saved = resourceRepository.save(res);
      resourceRateRepository.save(buildRate(saved.getId(), "BUDGETED", rate, DEFAULT_START));
      resourceRateRepository.save(buildRate(saved.getId(), "ACTUAL",
          rate.multiply(new BigDecimal("1.05")).setScale(3, RoundingMode.HALF_UP),
          DEFAULT_START));
      resCount++;
      rateCount += 2;
    }

    // Pass 2: direct labour trades from DPR-internal (skip dupes with pass 1).
    for (DirectLabourRow row : directRows) {
      String trade = row.position();
      if (trade == null || trade.isBlank()) continue;
      trade = trade.trim().replaceAll("\\s+", " ");
      String code = "BNK-MP-" + slug(trade);
      if (code.length() > 50) code = code.substring(0, 50);
      if (!seenCodes.add(code)) continue;
      if (resourceRepository.findByCode(code).isPresent()) continue;
      BigDecimal rate = manpowerDailyRateOmr(trade);
      ResourceRole role = resourceFactory.ensureRole("ROLE-" + slug(trade), "LABOR");
      Resource res = Resource.builder()
          .code(code)
          .name(trade)
          .resourceType(laborType)
          .role(role)
          .unit("Day")
          .calendarId(calendarId)
          .status(ResourceStatus.ACTIVE)
          .availability(BigDecimal.valueOf(100))
          .costPerUnit(rate)
          .sortOrder(sortOrder++)
          .build();
      Resource saved = resourceRepository.save(res);
      resourceRateRepository.save(buildRate(saved.getId(), "BUDGETED", rate, DEFAULT_START));
      resourceRateRepository.save(buildRate(saved.getId(), "ACTUAL",
          rate.multiply(new BigDecimal("1.05")).setScale(3, RoundingMode.HALF_UP),
          DEFAULT_START));
      resCount++;
      rateCount += 2;
    }

    log.info("[BNK] Seeded {} manpower resources + {} rates ({} norm-backed, {} direct-labour)",
        resCount, rateCount, normByTrade.size(),
        Math.max(0, resCount - normByTrade.size()));
  }

  // ────────────────────── Manpower Roles ──────────────────────
  /**
   * 43 indirect staff (workbook 1 sheet "Resource" left half) + 39 direct labour
   * rows (right half) → resource-domain {@link Role} rows. Daily OMR rate by tier
   * (deterministic from position name): PM=125, CM=110, Engineer=65, Foreman=35,
   * Operator=25, Mason=18, Carpenter=16, Helper=8.
   */
  private void seedManpowerResourceRoles() {
    List<IndirectStaffRow> indirect = reader.withWorkbook(
        OmanRoadProjectWorkbookReader.DPR_INTERNAL_PATH, reader::readIndirectStaff);
    List<DirectLabourRow> direct = reader.withWorkbook(
        OmanRoadProjectWorkbookReader.DPR_INTERNAL_PATH, reader::readDirectLabour);
    ResourceType laborType = resourceFactory.requireType("LABOR");
    int count = 0;
    int sortOrder = 0;
    java.util.Set<String> seenCodes = new java.util.HashSet<>();
    for (IndirectStaffRow r : indirect) {
      String pos = r.position();
      if (pos == null || pos.isBlank()) continue;
      String code = "BNK-ROLE-" + slug(pos);
      if (!seenCodes.add(code)) continue; // dedupe
      String trimmedCode = code.length() > 50 ? code.substring(0, 50) : code;
      if (resourceRoleRepository.findByCode(trimmedCode).isPresent()) continue;
      ResourceRole role = ResourceRole.builder()
          .code(trimmedCode)
          .name(truncate(pos, 100))
          .description("Indirect staff role — workbook 1 (Resource sheet, left half)")
          .resourceType(laborType)
          .productivityUnit("Day")
          .sortOrder(sortOrder++)
          .active(true)
          .build();
      resourceRoleRepository.save(role);
      count++;
    }
    for (DirectLabourRow r : direct) {
      String pos = r.position();
      if (pos == null || pos.isBlank()) continue;
      String code = "BNK-ROLE-" + slug(pos);
      if (!seenCodes.add(code)) continue;
      String trimmedCode = code.length() > 50 ? code.substring(0, 50) : code;
      if (resourceRoleRepository.findByCode(trimmedCode).isPresent()) continue;
      ResourceRole role = ResourceRole.builder()
          .code(trimmedCode)
          .name(truncate(pos, 100))
          .description("Direct labour role — workbook 1 (Resource sheet, right half)")
          .resourceType(laborType)
          .productivityUnit("Day")
          .sortOrder(sortOrder++)
          .active(true)
          .build();
      resourceRoleRepository.save(role);
      count++;
    }
    log.info("[BNK] Seeded {} manpower roles", count);
  }

  /** Deterministic OMR/day for a position label. */
  private BigDecimal manpowerDailyRateOmr(String position) {
    String p = position.toLowerCase(Locale.ROOT);
    double daily;
    if (p.contains("project manager")) daily = 125.0;
    else if (p.contains("construction manager")) daily = 110.0;
    else if (p.contains("planning") && p.contains("manager")) daily = 100.0;
    else if (p.contains("hse manager") || p.contains("safety manager")) daily = 70.0;
    else if (p.contains("qa/qc") || p.contains("qa qc") || p.contains("quality")) daily = 60.0;
    else if (p.contains("project engineer") || p.contains("site engineer")
        || p.contains("structural eng") || p.contains("civil eng")) daily = 65.0;
    else if (p.contains("surveyor") || p.contains("draftsman")) daily = 45.0;
    else if (p.contains("foreman")) daily = 35.0;
    else if (p.contains("operator") || p.contains("driver")) daily = 25.0;
    else if (p.contains("mason")) daily = 18.0;
    else if (p.contains("carpenter") || p.contains("steel fixer")) daily = 16.0;
    else if (p.contains("electrician") || p.contains("plumber") || p.contains("welder")) daily = 20.0;
    else if (p.contains("helper") || p.contains("unskilled")) daily = 8.0;
    else if (p.contains("supervisor")) daily = 50.0;
    else if (p.contains("admin") || p.contains("clerk") || p.contains("storekeeper")) daily = 22.0;
    else daily = 12.0;
    return BigDecimal.valueOf(daily).setScale(4, RoundingMode.HALF_UP);
  }

  // ─────────────── ProjectLabourDeployments ──────────────────
  /**
   * For every {@link LabourDesignation} already seeded by {@link OmanLabourMasterSeeder}
   * (44 rows), create one {@link ProjectLabourDeployment} on this project. Worker counts
   * are deterministic by designation tier so the labour-deployment tab renders a credible
   * mix.
   */
  private void seedProjectLabourDeployments(UUID projectId) {
    List<LabourDesignation> designations = labourDesignationRepository.findAll();
    if (designations.isEmpty()) {
      log.warn("[BNK] No LabourDesignation rows found — skipping ProjectLabourDeployment seed");
      return;
    }
    int count = 0;
    for (LabourDesignation d : designations) {
      if (projectLabourDeploymentRepository.existsByProjectIdAndDesignationId(projectId, d.getId())) {
        continue;
      }
      int workers = workerCountFor(d.getDesignation());
      ProjectLabourDeployment dep = ProjectLabourDeployment.builder()
          .projectId(projectId)
          .designationId(d.getId())
          .workerCount(workers)
          .actualDailyRate(d.getDefaultDailyRate())
          .notes("Auto-seeded by OmanRoadProjectSeeder for project " + PROJECT_CODE)
          .build();
      projectLabourDeploymentRepository.save(dep);
      count++;
    }
    log.info("[BNK] Seeded {} ProjectLabourDeployment rows", count);
  }

  /** Deterministic worker count for a designation label. */
  private int workerCountFor(String designation) {
    if (designation == null) return 1;
    String d = designation.toLowerCase(Locale.ROOT);
    if (d.contains("project manager")) return 1;
    if (d.contains("construction manager") || d.contains("planning manager")) return 1;
    if (d.contains("hse manager")) return 1;
    if (d.contains("resident engineer") || d.contains("re ")) return 2;
    if (d.contains("qa/qc") || d.contains("quality") || d.contains("qs ")
        || d.contains("quantity surveyor")) return 2;
    if (d.contains("site engineer") || d.contains("project engineer")
        || d.contains("structural engineer") || d.contains("civil engineer")) return 3;
    if (d.contains("surveyor") || d.contains("draftsman")) return 2;
    if (d.contains("foreman")) return 4;
    if (d.contains("operator") || d.contains("driver")) return 6;
    if (d.contains("mason")) return 12;
    if (d.contains("carpenter") || d.contains("steel fixer")) return 8;
    if (d.contains("electrician") || d.contains("plumber") || d.contains("welder")) return 4;
    if (d.contains("helper") || d.contains("unskilled")) return 24;
    if (d.contains("storekeeper") || d.contains("admin") || d.contains("clerk")) return 1;
    if (d.contains("supervisor")) return 4;
    return 2;
  }

  // ───────────────────────── Activities ──────────────────────
  /**
   * One Activity per BOQ row, with WBS link + duration derived from matching productivity
   * norm (or sensible default), and start dates staggered by L1 prefix per plan §4.13.
   *
   * <p>Percent-complete distribution per plan §Operability — deterministic 20-bucket
   * spread keyed off {@code activityIndex % 20} so reruns produce identical state and the
   * user has a healthy mix to demo "update progress" against:
   * <ul>
   *   <li>buckets 0–4  (25%): {@code NOT_STARTED} at 0%</li>
   *   <li>buckets 5–14 (50%): {@code IN_PROGRESS} at 10..80% (formula
   *       {@code (idx*7) % 71 + 10}); actualStartDate = plannedStart + random(0..3)</li>
   *   <li>buckets 15–18 (20%): {@code COMPLETED} at 100%; actualStart + actualFinish
   *       (finish + random(-2..5))</li>
   *   <li>bucket 19 (5%): on-hold/cancelled (alternating) at 25%; encoded via
   *       {@code suspendDate} + a marker in {@code notes} since the
   *       {@link ActivityStatus} enum has only NOT_STARTED / IN_PROGRESS / COMPLETED</li>
   * </ul>
   */
  private void seedActivities(UUID projectId, UUID calendarId, Map<String, UUID> wbs,
                              List<BoqItem> savedBoqs, List<ProductivityNormRow> norms) {
    // Plan §4.13 — start offsets in days for each L1 group.
    Map<String, Integer> prefixOffsetDays = Map.of(
        "9", 0,    // Site Facilities
        "1", 0,    // Preliminaries
        "2", 30,   // Demolition
        "3", 60,   // Earthworks
        "5", 90,   // Drainage
        "6", 60,   // Bridges (parallel)
        "7", 120,  // Utilities
        "4", 240,  // Pavement
        "8", 540   // Road Furniture
    );
    int sortOrder = 0;
    int total = 0;
    // No RNG: actualStart/Finish jitter is derived from a stable hash of the activity code so
    // reruns are identical and EVM (which uses these dates) reproduces.

    List<Activity> activities = new ArrayList<>();
    int withinGroupCounter = 0;
    String lastPrefix = null;
    int idx = 0;
    int notStarted = 0, inProgress = 0, completed = 0, suspended = 0;
    for (BoqItem b : savedBoqs) {
      String code = b.getItemNo();
      String prefix = l1Prefix(code);
      if (prefix == null) prefix = "1";
      UUID wbsNodeId = b.getWbsNodeId();
      if (wbsNodeId == null) wbsNodeId = wbs.get(prefix);
      if (wbsNodeId == null) wbsNodeId = wbs.get("WBS-1"); // last-resort
      if (wbsNodeId == null) {
        // Find any WBS node we created — ensure activity row at least has a parent.
        wbsNodeId = wbs.values().stream().findFirst().orElse(null);
      }
      if (wbsNodeId == null) continue;

      long durationDays = estimateDurationDays(b, norms);
      // within-group sort: cheap sequential counter, reset at prefix change.
      if (!prefix.equals(lastPrefix)) { withinGroupCounter = 0; lastPrefix = prefix; }
      int withinIdx = withinGroupCounter++;

      int groupOffset = prefixOffsetDays.getOrDefault(prefix, 0);
      // Stagger within prefix every 7 days (denser than NHAI's 30) since we have ~204 items.
      LocalDate start = DEFAULT_START.plusDays((long) groupOffset + (withinIdx * 7L));
      LocalDate finish = start.plusDays(durationDays);

      // Operability spread per plan §Operability — deterministic 20-bucket distribution.
      ActivityStatus status;
      double pct;
      LocalDate actualStart = null;
      LocalDate actualFinish = null;
      LocalDate suspendDate = null;
      String suspendNote = null;
      int bucket = idx % 20;
      if (bucket <= 4) {
        // Buckets 0..4 (25%) — NOT_STARTED at 0%
        status = ActivityStatus.NOT_STARTED;
        pct = 0.0;
        notStarted++;
      } else if (bucket <= 14) {
        // Buckets 5..14 (50%) — IN_PROGRESS at 10..80%
        status = ActivityStatus.IN_PROGRESS;
        pct = (idx * 7) % 71 + 10; // 10..80 inclusive
        // Stable +0..3 day actualStart jitter from idx (no RNG).
        actualStart = start.plusDays(idx % 4);
        inProgress++;
      } else if (bucket <= 18) {
        // Buckets 15..18 (20%) — COMPLETED at 100%
        status = ActivityStatus.COMPLETED;
        pct = 100.0;
        actualStart = start.plusDays(idx % 4);          // +0..3 (stable)
        actualFinish = finish.plusDays((idx % 8) - 2);  // -2..+5 (stable)
        completed++;
      } else {
        // Bucket 19 (5%) — on-hold or cancelled, alternating. ActivityStatus enum has only
        // NOT_STARTED / IN_PROGRESS / COMPLETED — encode "on hold / cancelled" via
        // suspendDate + a marker in notes so downstream EVM and reports can detect it.
        status = ActivityStatus.NOT_STARTED;
        pct = 25.0;
        boolean onHold = (idx % 2 == 0);
        suspendDate = start.plusDays(7);
        suspendNote = onHold
            ? "ON_HOLD — pending design clarification (seeder operability marker)."
            : "CANCELLED — descoped via VO-002 (seeder operability marker).";
        suspended++;
      }

      Activity a = new Activity();
      a.setCode("ACT-" + (code.length() > 16 ? code.substring(0, 16) : code));
      a.setName(truncate(b.getDescription() != null ? b.getDescription()
          : "Activity " + code, 100));
      String descPrefix = (suspendNote != null) ? "[" + suspendNote.split(" ")[0] + "] " : "";
      a.setDescription(descPrefix + (b.getDescription() == null ? "" : b.getDescription()));
      a.setProjectId(projectId);
      a.setWbsNodeId(wbsNodeId);
      a.setActivityType(ActivityType.TASK_DEPENDENT);
      a.setDurationType(DurationType.FIXED_DURATION_AND_UNITS);
      a.setStatus(status);
      a.setOriginalDuration((double) durationDays);
      double remaining = (status == ActivityStatus.COMPLETED) ? 0.0
          : durationDays * (1.0 - (pct / 100.0));
      a.setRemainingDuration(remaining);
      a.setAtCompletionDuration((double) durationDays);
      a.setPlannedStartDate(start);
      a.setPlannedFinishDate(finish);
      a.setEarlyStartDate(start);
      a.setEarlyFinishDate(finish);
      a.setActualStartDate(actualStart);
      a.setActualFinishDate(actualFinish);
      a.setSuspendDate(suspendDate);
      a.setNotes(suspendNote);
      a.setCalendarId(calendarId);
      a.setPercentComplete(pct);
      a.setPhysicalPercentComplete(pct);
      a.setIsCritical(false);
      a.setSortOrder(sortOrder++);
      a.setChainageFromM(CHAINAGE_START_M);
      a.setChainageToM(CHAINAGE_END_M);

      activities.add(a);
      total++;
      idx++;
    }
    activityRepository.saveAll(activities);
    backfillActivityWorkActivityLinks(projectId);
    log.info("[BNK] activity %% spread: NOT_STARTED={}, IN_PROGRESS={}, COMPLETED={}, on-hold/cancelled={}",
        notStarted, inProgress, completed, suspended);
    int relCount = seedActivityRelationships(projectId);
    log.info("[BNK] Seeded {} activities + {} relationships", total, relCount);
  }

  /**
   * Link every project activity to a {@code resource.work_activities} row by exact name
   * match (case-insensitive); for activities whose BOQ description doesn't match a master
   * activity, fall back to a deterministic round-robin assignment over the work-activity
   * pool. The link is required by Capacity Utilization, which joins
   * {@code daily_activity_resource_outputs → activities → work_activities} to look up
   * the productivity norm.
   *
   * <p>Idempotent — only updates rows where {@code work_activity_id IS NULL}.
   */
  private void backfillActivityWorkActivityLinks(UUID projectId) {
    List<Activity> activities = activityRepository.findByProjectId(projectId);
    if (activities.isEmpty()) return;
    List<UUID> waPool = workActivityRepository.findAll().stream()
        .sorted(Comparator.comparing(wa -> wa.getName() == null ? "" : wa.getName()))
        .map(WorkActivity::getId)
        .toList();
    if (waPool.isEmpty()) {
      log.warn("[BNK] no WorkActivity rows — Capacity Utilization will be empty");
      return;
    }
    int linkedExact = 0, linkedRoundRobin = 0, alreadyLinked = 0;
    int idx = 0;
    List<Activity> dirty = new ArrayList<>();
    for (Activity a : activities) {
      if (a.getWorkActivityId() != null) {
        alreadyLinked++;
        idx++;
        continue;
      }
      UUID resolved = workActivityRepository.findByNameIgnoreCase(a.getName())
          .map(WorkActivity::getId)
          .orElse(null);
      if (resolved != null) {
        a.setWorkActivityId(resolved);
        linkedExact++;
      } else {
        a.setWorkActivityId(waPool.get(idx % waPool.size()));
        linkedRoundRobin++;
      }
      dirty.add(a);
      idx++;
    }
    if (!dirty.isEmpty()) {
      activityRepository.saveAll(dirty);
    }
    log.info("[BNK] Activity→WorkActivity links: {} exact, {} round-robin ({} already linked)",
        linkedExact, linkedRoundRobin, alreadyLinked);
  }

  /** Compute duration in days using best-match productivity norm; clamp [14, 240]. */
  private long estimateDurationDays(BoqItem b, List<ProductivityNormRow> norms) {
    BigDecimal qty = b.getBoqQty();
    if (qty == null || qty.signum() == 0) return 30L;
    BigDecimal dailyOutput = findDailyOutput(b.getDescription(), norms);
    if (dailyOutput == null || dailyOutput.signum() == 0) return 30L;
    int crews = qty.compareTo(new BigDecimal("10000")) >= 0 ? 3
        : qty.compareTo(new BigDecimal("500")) >= 0 ? 2 : 1;
    BigDecimal days = qty.divide(dailyOutput.multiply(BigDecimal.valueOf(crews)),
        0, RoundingMode.CEILING);
    long d;
    try {
      d = days.longValueExact();
    } catch (ArithmeticException ex) {
      d = 60L;
    }
    return Math.max(14L, Math.min(240L, d));
  }

  /** Best-effort norm lookup by substring match on activity name. */
  private BigDecimal findDailyOutput(String description, List<ProductivityNormRow> norms) {
    if (description == null) return null;
    String needle = description.toLowerCase(Locale.ROOT);
    for (ProductivityNormRow n : norms) {
      if (n.activity() == null) continue;
      String key = n.activity().toLowerCase(Locale.ROOT)
          .split("[–\\-(]", 2)[0].trim();
      if (!key.isEmpty() && needle.contains(key)) {
        return n.outputPerDay();
      }
    }
    return null;
  }

  // ───────────────── Activity Relationships ───────────────────
  /**
   * Intra-group FS chain for activities sharing the same L1 prefix, plus a few
   * cross-section relationships to demonstrate SS / FF lags. Returns total count.
   */
  private int seedActivityRelationships(UUID projectId) {
    List<Activity> activities = activityRepository.findByProjectId(projectId).stream()
        .filter(a -> a.getCode() != null && a.getCode().startsWith("ACT-"))
        .sorted(Comparator.comparing(this::numericItemKey))
        .toList();
    if (activities.size() < 2) return 0;

    LinkedHashMap<String, List<Activity>> byPrefix = new LinkedHashMap<>();
    for (Activity a : activities) {
      String itemNo = a.getCode().substring("ACT-".length());
      String prefix = l1Prefix(itemNo);
      if (prefix == null) continue;
      byPrefix.computeIfAbsent(prefix, p -> new ArrayList<>()).add(a);
    }

    int created = 0;
    Activity prevGroupTail = null;
    for (var entry : byPrefix.entrySet()) {
      List<Activity> group = entry.getValue();
      // Intra-group FS(0) chain
      for (int i = 1; i < group.size(); i++) {
        if (persistRelationship(projectId, group.get(i - 1), group.get(i),
            RelationshipType.FINISH_TO_START, 0.0)) created++;
      }
      // Inter-group FS(0) — last of prev → first of this
      if (prevGroupTail != null && !group.isEmpty()) {
        if (persistRelationship(projectId, prevGroupTail, group.get(0),
            RelationshipType.FINISH_TO_START, 0.0)) created++;
      }
      if (!group.isEmpty()) prevGroupTail = group.get(group.size() - 1);
    }

    // Cross-section samples — Earthworks (3) → Bridges (6) SS(30); Pavement (4) FF(0) Markings (8).
    List<Activity> earth = byPrefix.getOrDefault("3", List.of());
    List<Activity> bridges = byPrefix.getOrDefault("6", List.of());
    if (!earth.isEmpty() && !bridges.isEmpty()) {
      if (persistRelationship(projectId, earth.get(0), bridges.get(0),
          RelationshipType.START_TO_START, 30.0)) created++;
    }
    List<Activity> pavement = byPrefix.getOrDefault("4", List.of());
    List<Activity> furniture = byPrefix.getOrDefault("8", List.of());
    if (!pavement.isEmpty() && !furniture.isEmpty()) {
      Activity lastPaver = pavement.get(pavement.size() - 1);
      if (persistRelationship(projectId, lastPaver, furniture.get(0),
          RelationshipType.FINISH_TO_FINISH, 0.0)) created++;
    }
    return created;
  }

  private boolean persistRelationship(UUID projectId, Activity pred, Activity succ,
                                      RelationshipType type, double lag) {
    if (activityRelationshipRepository
        .existsByPredecessorActivityIdAndSuccessorActivityId(pred.getId(), succ.getId())) {
      return false;
    }
    ActivityRelationship rel = new ActivityRelationship();
    rel.setProjectId(projectId);
    rel.setPredecessorActivityId(pred.getId());
    rel.setSuccessorActivityId(succ.getId());
    rel.setRelationshipType(type);
    rel.setLag(lag);
    rel.setIsExternal(false);
    activityRelationshipRepository.save(rel);
    return true;
  }

  /** Sort activity codes numerically segment-by-segment so 1.10 sorts after 1.9. */
  private String numericItemKey(Activity a) {
    String itemNo = a.getCode().substring("ACT-".length());
    StringBuilder key = new StringBuilder();
    for (String seg : itemNo.split("\\.")) {
      // Strip parens / brackets so segment is purely numeric where possible.
      String numeric = stripParens(seg);
      try {
        key.append(String.format("%08d", Integer.parseInt(numeric)));
      } catch (NumberFormatException e) {
        key.append(numeric);
      }
      key.append('.');
    }
    return key.toString();
  }

  // ───────────────────────── Stretches ────────────────────────
  /**
   * 4 stretches over the 41 km corridor (10.5 km each) + round-robin
   * StretchActivityLink rows so every stretch gets BOQ items linked.
   */
  private void seedStretches(UUID projectId, List<BoqItem> savedBoqs) {
    Object[][] segs = {
        {"BNK-S1", "Barka Junction → Wadi Al Hattat",         0L,    10500L},
        {"BNK-S2", "Wadi Al Hattat → Mid-corridor Bridge",    10500L, 21000L},
        {"BNK-S3", "Mid-corridor Bridge → Nakhal Approach",   21000L, 31500L},
        {"BNK-S4", "Nakhal Approach → Nakhal Roundabout",     31500L, 41000L}
    };
    List<Stretch> stretches = new ArrayList<>();
    for (Object[] s : segs) {
      String code = (String) s[0];
      Stretch stretch = Stretch.builder()
          .projectId(projectId)
          .stretchCode(code)
          .name((String) s[1])
          .fromChainageM((Long) s[2])
          .toChainageM((Long) s[3])
          .lengthM((Long) s[3] - (Long) s[2])
          .packageCode(code)
          .status(StretchStatus.ACTIVE)
          .milestoneName(s[1] + " — substantial completion")
          .targetDate(DEFAULT_FINISH)
          .build();
      stretches.add(stretchRepository.save(stretch));
    }

    // Round-robin BOQ items across stretches (the link table targets boqItemId).
    int linkCount = 0;
    for (int i = 0; i < savedBoqs.size(); i++) {
      Stretch target = stretches.get(i % stretches.size());
      StretchActivityLink link = new StretchActivityLink();
      link.setStretchId(target.getId());
      link.setBoqItemId(savedBoqs.get(i).getId());
      stretchActivityLinkRepository.save(link);
      linkCount++;
    }
    log.info("[BNK] Seeded {} stretches + {} stretch links", stretches.size(), linkCount);
  }

  // ─────────────────────── Sanity Check ───────────────────────
  /**
   * Recompute total BOQ amounts from DB and compare with the (synthetic-augmented)
   * expected values. Demo-data tolerance: log a warning if drift > 1%; never throw.
   * Mirror NHAI {@code sanityCheck} (lines 1035-1063) but downgraded to log-only.
   */
  private void assertBoqRollupConsistent(UUID projectId, List<BoqRow> boqRows) {
    BigDecimal expectedBoq = BigDecimal.ZERO;
    BigDecimal expectedBudget = BigDecimal.ZERO;
    BigDecimal expectedActual = BigDecimal.ZERO;
    int idx = 0;
    for (BoqRow r : boqRows) {
      BigDecimal amount = computeBoqAmount(r, idx++);
      expectedBoq = expectedBoq.add(amount);
      expectedBudget = expectedBudget.add(amount);
      // Assume actual ≈ amount × executed-fraction × 1.03; this is rough.
      expectedActual = expectedActual.add(amount.multiply(new BigDecimal("0.30"))
          .setScale(2, RoundingMode.HALF_UP));
    }

    List<BoqItem> items = boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId);
    BigDecimal storedBoq = BigDecimal.ZERO;
    BigDecimal storedBudget = BigDecimal.ZERO;
    BigDecimal storedActual = BigDecimal.ZERO;
    for (BoqItem b : items) {
      storedBoq = storedBoq.add(nz(b.getBoqAmount()));
      storedBudget = storedBudget.add(nz(b.getBudgetedAmount()));
      storedActual = storedActual.add(nz(b.getActualAmount()));
    }

    log.info("[BNK] BOQ sanity — items={} | BOQ stored={} expected~={} | "
            + "Budget stored={} | Actual stored={} (currency=OMR)",
        items.size(),
        storedBoq.setScale(0, RoundingMode.HALF_UP),
        expectedBoq.setScale(0, RoundingMode.HALF_UP),
        storedBudget.setScale(0, RoundingMode.HALF_UP),
        storedActual.setScale(0, RoundingMode.HALF_UP));
    warnIfDriftBeyond1Pct("BOQ total", storedBoq, expectedBoq);
    warnIfDriftBeyond1Pct("Budget total", storedBudget, expectedBudget);
  }

  private void warnIfDriftBeyond1Pct(String label, BigDecimal actual, BigDecimal expected) {
    if (expected.signum() == 0) return;
    BigDecimal diff = actual.subtract(expected).abs();
    BigDecimal tolerance = expected.abs().multiply(new BigDecimal("0.01"));
    if (diff.compareTo(tolerance) > 0) {
      log.warn("[BNK] {} drift > 1%: stored={} expected={} (demo-data, not failing)",
          label, actual.toPlainString(), expected.toPlainString());
    }
  }

  // ─────────────────────────── Helpers ────────────────────────
  /** Compact upper-case code slug — e.g. "Excavator (JCB 210)" → "EXCAVATORJCB210". */
  private String slug(String s) {
    if (s == null) return "UNKNOWN";
    StringBuilder sb = new StringBuilder();
    for (char c : s.toUpperCase(Locale.ROOT).toCharArray()) {
      if (Character.isLetterOrDigit(c)) sb.append(c);
      if (sb.length() >= 25) break;
    }
    return sb.length() == 0 ? "UNKNOWN" : sb.toString();
  }

  private String truncate(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static String nullSafe(String s, String fallback) {
    return s == null || s.isBlank() ? fallback : s;
  }

  /** Read a JSON list of maps from the classpath. */
  private List<Map<String, Object>> readJsonList(String path) {
    try (InputStream in = new ClassPathResource(path).getInputStream()) {
      return objectMapper.readValue(in, new TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception e) {
      log.error("[BNK] failed to read JSON resource '{}': {}", path, e.getMessage());
      return List.of();
    }
  }
}
