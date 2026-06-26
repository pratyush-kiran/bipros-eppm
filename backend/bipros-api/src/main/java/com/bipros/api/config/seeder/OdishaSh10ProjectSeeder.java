package com.bipros.api.config.seeder;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityRelationship;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.ActivityType;
import com.bipros.activity.domain.model.DurationType;
import com.bipros.activity.domain.model.RelationshipType;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.calendar.domain.model.Calendar;
import com.bipros.calendar.domain.model.CalendarType;
import com.bipros.calendar.domain.model.CalendarWorkWeek;
import com.bipros.calendar.domain.model.DayType;
import com.bipros.calendar.domain.repository.CalendarRepository;
import com.bipros.calendar.domain.repository.CalendarWorkWeekRepository;
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
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceEquipmentDetails;
import com.bipros.resource.domain.model.ResourceMaterialDetails;
import com.bipros.resource.domain.model.ResourceRate;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceStatus;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.repository.MaterialConsumptionLogRepository;
import com.bipros.resource.application.service.WorkActivityService;
import com.bipros.resource.domain.model.WorkActivity;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Odisha Works Department SH-10 Bhubaneswar–Cuttack 4-laning &amp; Strengthening project seeder.
 *
 * <p>Programmatic counterpart to {@link NhaiRoadProjectSeeder} (which reads its data from an
 * Excel workbook). The Odisha project's data is fully hardcoded here — no workbook to maintain.
 *
 * <p>The project is sized for a "near-completion" demo: started 2024-08-01, planned finish
 * 2026-12-31, ~75% physical progress as of today's data date (2026-04-25). EVM target:
 * SPI 0.943 / CPI 0.948 (mild overrun). Historical depth: 21 monthly EVM snapshots, 9 RA
 * bills, ~120 DPRs, ~120 resource deployments, ~120 weather rows.
 *
 * <p>Sentinel: project lookup by code {@code OWD/SH10/OD/2025/001}. Skipped on re-run.
 *
 * @see NhaiRoadProjectSeeder for the structural shape (EPS/OBS/Calendar/WBS/BOQ/Activity).
 */
@Slf4j
@Component
@Profile("legacy-demo")
@Order(160)
@RequiredArgsConstructor
public class OdishaSh10ProjectSeeder implements CommandLineRunner {

    static final String PROJECT_CODE = "OWD/SH10/OD/2025/001";
    private static final LocalDate PLANNED_START  = LocalDate.of(2024, 8, 1);
    private static final LocalDate PLANNED_FINISH = LocalDate.of(2026, 12, 31);
    private static final LocalDate DATA_DATE      = LocalDate.of(2026, 4, 25);
    private static final long CHAINAGE_START_M = 0L;
    private static final long CHAINAGE_END_M   = 28_000L;

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
    private final ActivityRepository activityRepository;
    private final ActivityRelationshipRepository activityRelationshipRepository;
    private final javax.sql.DataSource dataSource;

    @Override
    public void run(String... args) {
        if (projectRepository.findByCode(PROJECT_CODE).isPresent()) {
            log.info("[OD-SH10] project '{}' already seeded, skipping", PROJECT_CODE);
            return;
        }
        log.info("[OD-SH10] seeding Odisha SH-10 Bhubaneswar–Cuttack project (~75% complete)…");

        UUID epsLeaf = seedEps();
        UUID obsLeaf = seedObs();
        UUID calendarId = seedCalendar();
        Project project = seedProject(epsLeaf, obsLeaf, calendarId);

        Map<String, UUID> wbs = seedWbs(project.getId());
        seedBoqItems(project.getId(), wbs);
        seedProductivityNorms();
        Map<String, UUID> resources = seedResourcesAndRates(calendarId);
        seedManpowerResourceRoles();
        seedActivities(project.getId(), calendarId, wbs);
        seedDailyProgressReports(project.getId(), wbs);
        seedResourceDeployments(project.getId());
        seedDailyWeather(project.getId());
        seedNextDayPlans(project.getId());
        seedMaterialConsumption(project.getId(), resources);

        log.info("[OD-SH10] WBS={} resources={} activities={} - structural seeding done",
                wbs.size(), resources.size(), activityRepository.findByProjectId(project.getId()).size());

        loadReportsSqlBundle(PROJECT_CODE);
    }

    /**
     * Mirrors {@link NhaiRoadProjectSeeder#loadReportsDataSqlBundle(String)} — runs the
     * SQL bundle under {@code seed-data/odisha-sh10/reports/} for the report panel rows
     * (EVM, contracts, RA bills, VOs, risks, funding, tenders, milestones, expenses, etc.).
     */
    private void loadReportsSqlBundle(String projectCode) {
        org.springframework.core.io.Resource[] files;
        try {
            var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
            files = resolver.getResources("classpath:seed-data/odisha-sh10/reports/*.sql");
        } catch (Exception e) {
            log.error("[OD-SH10 reports] could not enumerate SQL bundle: {}", e.getMessage(), e);
            return;
        }
        if (files.length == 0) {
            log.info("[OD-SH10 reports] no SQL bundle found — skipping");
            return;
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparing(org.springframework.core.io.Resource::getFilename));
        log.info("[OD-SH10 reports] running {} demo-data SQL file(s) for '{}'", files.length, projectCode);

        int ok = 0, failed = 0;
        for (var f : files) {
            try (var conn = dataSource.getConnection()) {
                boolean wasAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(true);
                try {
                    log.info("[OD-SH10 reports] executing {}", f.getFilename());
                    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(conn, f);
                    ok++;
                } finally {
                    conn.setAutoCommit(wasAutoCommit);
                }
            } catch (Exception e) {
                failed++;
                log.error("[OD-SH10 reports] {} failed: {}", f.getFilename(), e.getMessage());
            }
        }
        log.info("[OD-SH10 reports] bundle finished — {} succeeded, {} failed", ok, failed);
    }

    // ─────────────────────────── EPS ───────────────────────────
    private UUID seedEps() {
        EpsNode owd = saveEps("OWD", "Odisha Works Department", null, 0);
        EpsNode od = saveEps("OWD-OD", "OWD State Highway Wing — Odisha", owd.getId(), 0);
        EpsNode sh10 = saveEps("SH10-BBSR-CTC", "SH-10 Bhubaneswar–Cuttack Corridor", od.getId(), 0);
        return sh10.getId();
    }

    private EpsNode saveEps(String code, String name, UUID parentId, int sortOrder) {
        EpsNode n = new EpsNode();
        n.setCode(code);
        n.setName(name);
        n.setParentId(parentId);
        n.setSortOrder(sortOrder);
        return epsNodeRepository.save(n);
    }

    // ─────────────────────────── OBS ───────────────────────────
    private UUID seedObs() {
        ObsNode owdHq = saveObs("OWD-HQ", "OWD Headquarters — Bhubaneswar", null, 0);
        ObsNode eeBbsr = saveObs("OWD-EE-BBSR", "OWD Executive Engineer Office — Bhubaneswar PIU", owdHq.getId(), 0);
        ObsNode meil = saveObs("MEIL-BBSR", "Megha Engineering & Infrastructures Ltd — Bhubaneswar Site Office", eeBbsr.getId(), 0);
        ObsNode pmTeam = saveObs("SH10-PM-TEAM", "Er. Subrat K. Mohanty — SH-10 Project Team", meil.getId(), 0);
        return pmTeam.getId();
    }

    private ObsNode saveObs(String code, String name, UUID parentId, int sortOrder) {
        ObsNode o = new ObsNode();
        o.setCode(code);
        o.setName(name);
        o.setParentId(parentId);
        o.setSortOrder(sortOrder);
        return obsNodeRepository.save(o);
    }

    // ────────────────────────── Calendar ───────────────────────
    private UUID seedCalendar() {
        Calendar cal = Calendar.builder()
                .code("SH10-OWD-6day")
                .name("OWD SH-10 6-day Calendar")
                .description("Mon-Sat WORKING 9 hrs/day (OWD norm); Sunday NON_WORKING.")
                .calendarType(CalendarType.GLOBAL)
                .isDefault(false)
                .standardWorkHoursPerDay(9.0)
                .standardWorkDaysPerWeek(6)
                .build();
        Calendar saved = calendarRepository.save(cal);
        for (DayOfWeek day : DayOfWeek.values()) {
            boolean working = day != DayOfWeek.SUNDAY;
            CalendarWorkWeek ww = CalendarWorkWeek.builder()
                    .calendarId(saved.getId())
                    .dayOfWeek(day)
                    .dayType(working ? DayType.WORKING : DayType.NON_WORKING)
                    .totalWorkHours(working ? 9.0 : 0.0)
                    .startTime1(working ? LocalTime.of(7, 0) : null)
                    .endTime1(working ? LocalTime.of(12, 0) : null)
                    .startTime2(working ? LocalTime.of(13, 0) : null)
                    .endTime2(working ? LocalTime.of(17, 0) : null)
                    .build();
            calendarWorkWeekRepository.save(ww);
        }
        return saved.getId();
    }

    // ────────────────────────── Project ────────────────────────
    private Project seedProject(UUID epsLeafId, UUID obsLeafId, UUID calendarId) {
        Project p = new Project();
        p.setCode(PROJECT_CODE);
        p.setName("SH-10 Bhubaneswar–Cuttack 4-laning & Strengthening");
        p.setDescription("OWD-funded 4-laning of SH-10 corridor between Bhubaneswar and Cuttack. "
                + "Length: 28.000 km. Contract value: ₹ 452.50 crores. EPC contract awarded to "
                + "Megha Engineering & Infrastructures Ltd. ~75% physical complete as of "
                + "2026-04-25. Source: OWD project demo data.");
        p.setEpsNodeId(epsLeafId);
        p.setObsNodeId(obsLeafId);
        p.setPlannedStartDate(PLANNED_START);
        p.setPlannedFinishDate(PLANNED_FINISH);
        p.setDataDate(DATA_DATE);
        p.setStatus(ProjectStatus.ACTIVE);
        p.setPriority(1);
        p.setCategory("STATE_HIGHWAY");
        p.setMorthCode("SH-10");
        p.setFromChainageM(CHAINAGE_START_M);
        p.setToChainageM(CHAINAGE_END_M);
        p.setFromLocation("Bhubaneswar (Km 0+000)");
        p.setToLocation("Cuttack (Km 28+000)");
        p.setTotalLengthKm(BigDecimal.valueOf(28.000).setScale(3, RoundingMode.HALF_UP));
        p.setCalendarId(calendarId);
        return projectRepository.save(p);
    }

    // ────────────────────────── WBS ────────────────────────────
    /** 7 L2 packages with realistic Odisha SH-10 budgets. Total = ₹ 452.50 crores. */
    private record WbsPackage(String code, String name, String groupKey, double budgetCrores, double percentComplete) {}

    private static final List<WbsPackage> WBS_PACKAGES = List.of(
            new WbsPackage("WBS-1", "Earthwork — excavation, embankment, sub-grade",      "1",  65.00, 98.0),
            new WbsPackage("WBS-2", "Sub-base courses (GSB + WMM)",                       "2",  85.00, 92.0),
            new WbsPackage("WBS-3", "Bituminous courses (DBM + BC)",                      "3", 140.00, 72.0),
            new WbsPackage("WBS-4", "Cross-drainage works (CD, culverts, side drains)",   "4",  40.00, 80.0),
            new WbsPackage("WBS-5", "Road furniture (markings, signage, kerbs, barriers)","5",  25.00, 30.0),
            new WbsPackage("WBS-6", "Structures (2 minor bridges + 1 ROB at Cuttack)",    "6",  72.50, 65.0),
            new WbsPackage("WBS-7", "Misc / utility shifting / toll plaza approach",      "7",  25.00, 10.0)
    );

    private Map<String, UUID> seedWbs(UUID projectId) {
        WbsNode rootNode = new WbsNode();
        rootNode.setCode("WBS-ROOT");
        rootNode.setName("SH-10 Bhubaneswar–Cuttack — Root");
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

        Map<String, UUID> wbs = new LinkedHashMap<>();
        int order = 0;
        for (WbsPackage pkg : WBS_PACKAGES) {
            WbsNode n = new WbsNode();
            n.setCode(pkg.code());
            n.setName(pkg.name());
            n.setParentId(root);
            n.setProjectId(projectId);
            n.setSortOrder(order++);
            n.setWbsLevel(2);
            n.setWbsType(WbsType.PACKAGE);
            n.setPhase(WbsPhase.CONSTRUCTION);
            n.setWbsStatus(WbsStatus.ACTIVE);
            n.setBudgetCrores(BigDecimal.valueOf(pkg.budgetCrores()).setScale(2, RoundingMode.HALF_UP));
            n.setSummaryPercentComplete(pkg.percentComplete());
            n.setChainageFromM(CHAINAGE_START_M);
            n.setChainageToM(CHAINAGE_END_M);
            wbs.put(pkg.groupKey(), wbsNodeRepository.save(n).getId());
        }
        return wbs;
    }

    // ─────────────────────────── BOQ ───────────────────────────
    /** BOQ row in workbook-shape — used by both BOQ + Activity seeders. */
    private record BoqRowDef(String itemNo, String description, String unit,
                             double qty, double rate, double budgetedRate,
                             double qtyExecuted, double actualRate) {}

    private static final List<BoqRowDef> BOQ_ROWS = List.of(
            // 1.x Earthwork (~₹65 cr), ~98% complete
            new BoqRowDef("1.1", "Earthwork — Excavation in all types of soil",                       "cum",  450_000,  280, 280, 441_000, 295),
            new BoqRowDef("1.2", "Embankment construction with approved fill material",              "cum",  680_000,  240, 240, 666_400, 252),
            new BoqRowDef("1.3", "Sub-grade preparation and compaction (95% MDD)",                   "cum",  165_000,  180, 180, 161_700, 188),
            // 2.x Sub-base (~₹85 cr), ~92%
            new BoqRowDef("2.1", "Granular Sub-Base (GSB) — 200 mm compacted",                       "cum",  140_000, 1850,1850, 128_800,1920),
            new BoqRowDef("2.2", "Wet Mix Macadam (WMM) — 250 mm compacted",                         "cum",  175_000, 2050,2050, 161_000,2120),
            new BoqRowDef("2.3", "Prime coat — bituminous emulsion",                                 "sqm",  280_000,   95,  95, 257_600,  98),
            // 3.x Bituminous (~₹140 cr), ~72%
            new BoqRowDef("3.1", "Dense Bituminous Macadam (DBM) — 50 mm",                           "cum",   28_000, 8950,8950,  20_160,9450),
            new BoqRowDef("3.2", "Bituminous Concrete (BC) wearing course — 40 mm",                  "cum",   22_400, 9550,9550,  16_128,9890),
            new BoqRowDef("3.3", "Tack coat — bituminous emulsion",                                  "sqm",  280_000,   75,  75, 201_600,  78),
            new BoqRowDef("3.4", "Bitumen VG-30 supply ex-IOC Paradip (incl. transport + loss)",     "MT",     2_400,52000,52000, 1_728,53800),
            // 4.x Drainage (~₹40 cr), ~80%
            new BoqRowDef("4.1", "Catch water drains — RR masonry",                                  "rm",     8_400, 1850,1850,  6_720,1920),
            new BoqRowDef("4.2", "Box culverts — RCC M25 (1.5x1.5m, span 3m)",                       "each",      18,1200000,1200000, 14,1245000),
            new BoqRowDef("4.3", "Pipe culverts — NP3 RCC 900 mm dia",                               "each",      32, 320000, 320000, 26, 332000),
            new BoqRowDef("4.4", "Side drains — earth + RR lining",                                  "rm",    25_200,  450, 450, 20_160, 472),
            // 5.x Road furniture (~₹25 cr), ~30%
            new BoqRowDef("5.1", "Thermoplastic road markings",                                      "sqm",    8_400, 2200,2200,  2_520,2280),
            new BoqRowDef("5.2", "Retro-reflective signboards (incl. gantries)",                     "each",      82,38000,38000,     24,40500),
            new BoqRowDef("5.3", "Precast kerb stones along median",                                 "rm",    52_000,  680, 680, 15_600, 695),
            new BoqRowDef("5.4", "W-beam metal crash barriers",                                      "rm",    36_400, 2450,2450, 10_920,2495),
            // 6.x Structures (~₹72.5 cr), ~65%
            new BoqRowDef("6.1", "Bridge pier — RCC M30 substructure (2 minor bridges)",             "cum",    1_850,72000,72000,  1_295,75800),
            new BoqRowDef("6.2", "Bridge superstructure — PSC girder + deck slab",                   "cum",    1_650,82000,82000,  1_073,86200),
            new BoqRowDef("6.3", "ROB at Cuttack approach — substructure",                           "cum",    1_240,75000,75000,    806,79500),
            new BoqRowDef("6.4", "ROB at Cuttack approach — superstructure",                         "cum",    1_120,84000,84000,    560,89200),
            // 7.x Misc / utility / toll plaza (~₹25 cr), ~10%
            new BoqRowDef("7.1", "Utility shifting (water + power + telecom) — OWD-borne",           "lump",       1,8500000,8500000,   1,8950000),
            new BoqRowDef("7.2", "Toll plaza approach — civil works",                                "lump",       1,7200000,7200000, 0.2,7600000),
            new BoqRowDef("7.3", "Site office, lab, batching plant setup — fully recoverable",       "lump",       1,3800000,3800000,   1,3850000),
            new BoqRowDef("7.4", "As-built drawings + handover documentation",                       "lump",       1,1200000,1200000, 0.0,1200000),
            new BoqRowDef("7.5", "Punch list rectification + warranty period reserve",               "lump",       1,3000000,3000000, 0.0,3000000)
    );

    private void seedBoqItems(UUID projectId, Map<String, UUID> wbs) {
        List<BoqItem> items = new ArrayList<>();
        for (BoqRowDef r : BOQ_ROWS) {
            String groupKey = r.itemNo().substring(0, 1);
            BoqItem b = BoqItem.builder()
                    .projectId(projectId)
                    .itemNo(r.itemNo())
                    .description(r.description())
                    .unit(r.unit())
                    .wbsNodeId(wbs.get(groupKey))
                    .boqQty(BigDecimal.valueOf(r.qty()))
                    .boqRate(BigDecimal.valueOf(r.rate()))
                    .budgetedRate(BigDecimal.valueOf(r.budgetedRate()))
                    .qtyExecutedToDate(BigDecimal.valueOf(r.qtyExecuted()))
                    .actualRate(BigDecimal.valueOf(r.actualRate()))
                    .build();
            BoqCalculator.recompute(b);
            items.add(b);
        }
        boqItemRepository.saveAll(items);
        log.info("[OD-SH10] seeded {} BOQ items", items.size());
    }

    // ────────────────────── Productivity Norms ─────────────────
    private void seedProductivityNorms() {
        record NormDef(String type, String name, String unit, double opmd, int crew, double opd, double oph, double whpd, Double fuel, String spec, String remarks) {}
        List<NormDef> norms = List.of(
                new NormDef("EQUIPMENT", "Earthwork — Excavation",       "cum",   0,  1, 1200, 133.3, 9.0, 22.0, "JCB 220 + tipper", "Soft + medium soil"),
                new NormDef("EQUIPMENT", "GSB laying + compaction",      "cum",   0,  1,  650, 72.2,  9.0, 12.0, "Motor grader + roller", "200 mm lift"),
                new NormDef("EQUIPMENT", "WMM laying + compaction",      "cum",   0,  1,  580, 64.4,  9.0, 13.0, "Motor grader + roller", "250 mm lift"),
                new NormDef("EQUIPMENT", "DBM paving",                   "cum",   0,  1,  280, 31.1,  9.0, 28.0, "Sensor paver + tandem roller", "Hot mix at 145°C"),
                new NormDef("EQUIPMENT", "BC paving",                    "cum",   0,  1,  240, 26.7,  9.0, 28.0, "Sensor paver + PTR", "Hot mix at 145°C"),
                new NormDef("EQUIPMENT", "Box culvert RCC casting",      "cum",   0,  1,   45,  5.0,  9.0,  5.0, "Mixer + vibrator", "M25 grade"),
                new NormDef("MANPOWER",  "Earthwork supervision",        "cum",  280, 4, 1120,  0.0,  9.0, null, "1 supervisor + 3 helpers", null),
                new NormDef("MANPOWER",  "Bituminous mat finishing",     "cum",   90, 6, 540,   0.0,  9.0, null, "1 mason + 5 unskilled", null),
                new NormDef("MANPOWER",  "Bridge pier shuttering",       "cum",   25, 8, 200,   0.0,  9.0, null, "1 carpenter + 7 helpers", null),
                new NormDef("MANPOWER",  "Road marking (thermoplastic)", "sqm",  120, 3, 360,   0.0,  9.0, null, "1 marker + 2 helpers", null)
        );
        List<ProductivityNorm> all = new ArrayList<>();
        for (NormDef n : norms) {
            WorkActivity wa = workActivityService.findOrCreateByName(n.name(), n.unit());
            all.add(ProductivityNorm.builder()
                    .normType(com.bipros.resource.domain.model.ProductivityNormType.valueOf(n.type()))
                    .workActivity(wa)
                    .activityName(wa.getName())
                    .unit(n.unit())
                    .outputPerManPerDay(BigDecimal.valueOf(n.opmd()))
                    .crewSize(n.crew())
                    .outputPerDay(BigDecimal.valueOf(n.opd()))
                    .outputPerHour(BigDecimal.valueOf(n.oph()))
                    .workingHoursPerDay(n.whpd())
                    .fuelLitresPerHour(n.fuel() == null ? null : BigDecimal.valueOf(n.fuel()))
                    .equipmentSpec(n.spec())
                    .remarks(n.remarks())
                    .build());
        }
        productivityNormRepository.saveAll(all);
        log.info("[OD-SH10] seeded {} productivity norms", all.size());
    }

    // ────────────────────── Resources + Rates ──────────────────
    /** Resource catalogue. Equipment hourly + unit rates set ~12% below NHAI baseline. */
    private record ResourceDef(String category, String description, String unit, double budgetedRate, double actualRate) {}

    private static final List<ResourceDef> RESOURCES = List.of(
            // Equipment (10) — hourly rates
            new ResourceDef("Equipment", "Excavator JCB 220",            "hour", 1750, 1810),
            new ResourceDef("Equipment", "Tipper TATA 2518 (10 cum)",    "hour",  900,  945),
            new ResourceDef("Equipment", "Motor Grader CAT 120K",        "hour", 2200, 2280),
            new ResourceDef("Equipment", "Tandem Roller 10T",            "hour", 1450, 1495),
            new ResourceDef("Equipment", "Hot Mix Plant 120 TPH",        "hour", 5800, 6050),
            new ResourceDef("Equipment", "Sensor Paver Vögele 1800",     "hour", 4200, 4380),
            new ResourceDef("Equipment", "Bitumen Sprayer 6000 L",       "hour", 1850, 1925),
            new ResourceDef("Equipment", "Concrete Mixer 1 cum",         "hour",  650,  680),
            new ResourceDef("Equipment", "Needle Vibrator 60 mm",        "hour",  180,  185),
            new ResourceDef("Equipment", "Water Tanker 9000 L",          "hour",  520,  545),
            // Material (12)
            new ResourceDef("Material",  "GSB aggregate 53-1.18 mm",     "cum",  1620, 1680),
            new ResourceDef("Material",  "WMM aggregate 53-0.075 mm",    "cum",  1820, 1885),
            new ResourceDef("Material",  "Bitumen VG-30",                "MT",  52000,53800),
            new ResourceDef("Material",  "Cement OPC-43",                "bag",   380,  395),
            new ResourceDef("Material",  "River sand (zone-II)",         "cum",  1450, 1505),
            new ResourceDef("Material",  "Crushed stone aggregate 20mm", "cum",  1850, 1920),
            new ResourceDef("Material",  "Crushed stone aggregate 10mm", "cum",  1920, 1995),
            new ResourceDef("Material",  "Steel TMT Fe-500D",            "MT",  62000,64500),
            new ResourceDef("Material",  "Form-work plywood 12 mm",      "sqm",   320,  335),
            new ResourceDef("Material",  "Geotextile non-woven 200gsm",  "sqm",    85,   88),
            new ResourceDef("Material",  "Curing compound (white)",      "L",      45,   47),
            new ResourceDef("Material",  "Anti-stripping additive",      "kg",    180,  189),
            // Sub-Contract (3)
            new ResourceDef("Sub-Contract", "Electrical works (toll plaza + signage)", "lump", 6500000, 6800000),
            new ResourceDef("Sub-Contract", "Toll-plaza civil works",                  "lump", 7200000, 7600000),
            new ResourceDef("Sub-Contract", "Landscaping + median plantation",         "lump", 1850000, 1950000)
    );

    /** Manpower roles. */
    private static final List<ResourceDef> MANPOWER = List.of(
            new ResourceDef("Manpower", "Site Engineer (BE Civil, 5+ yrs)",      "day", 1850, 1950),
            new ResourceDef("Manpower", "Lab Technician (M&C testing)",          "day",  950,  995),
            new ResourceDef("Manpower", "Surveyor (DGPS + Total Station)",       "day", 1450, 1520),
            new ResourceDef("Manpower", "Foreman (paving / earthwork)",          "day", 1250, 1310),
            new ResourceDef("Manpower", "Mason (skilled)",                       "day",  920,  965),
            new ResourceDef("Manpower", "Skilled Labour (paver/roller assist)",  "day",  720,  755),
            new ResourceDef("Manpower", "Unskilled Labour (general)",            "day",  520,  545),
            new ResourceDef("Manpower", "Equipment Operator (heavy plant)",      "day", 1450, 1520)
    );

    private Map<String, UUID> seedResourcesAndRates(UUID calendarId) {
        Map<String, UUID> byCode = new HashMap<>();
        int count = 0;
        for (ResourceDef r : RESOURCES) {
            String code = "OD-" + codePrefix(r.category()) + "-" + slug(r.description());
            String typeCode = resourceTypeCodeFor(r.category());
            ResourceType rt = resourceFactory.requireType(typeCode);
            ResourceRole role = resourceFactory.ensureRole(
                resourceRoleCodeFor(r.category(), r.description()), typeCode);
            BigDecimal costPerUnit = BigDecimal.valueOf(r.budgetedRate());
            Resource res = Resource.builder()
                    .code(code)
                    .name(r.description())
                    .resourceType(rt)
                    .role(role)
                    .unit(resourceUnitCodeFor(r.unit()))
                    .calendarId(calendarId)
                    .status(ResourceStatus.ACTIVE)
                    .availability(BigDecimal.valueOf(100))
                    .costPerUnit(costPerUnit)
                    .build();
            Resource saved = resourceRepository.save(res);
            byCode.put(code, saved.getId());
            if ("EQUIPMENT".equals(typeCode)) {
                resourceEquipmentDetailsRepository.save(ResourceEquipmentDetails.builder()
                    .resourceId(saved.getId())
                    .build());
            } else if ("MATERIAL".equals(typeCode)) {
                resourceMaterialDetailsRepository.save(ResourceMaterialDetails.builder()
                    .resourceId(saved.getId())
                    .baseUnit(resourceUnitCodeFor(r.unit()))
                    .build());
            }
            resourceRateRepository.save(buildRate(saved.getId(), "BUDGETED", BigDecimal.valueOf(r.budgetedRate())));
            resourceRateRepository.save(buildRate(saved.getId(), "ACTUAL", BigDecimal.valueOf(r.actualRate())));
            count++;
        }
        log.info("[OD-SH10] seeded {} resources (Equipment/Material/Sub-Contract) with BUDGETED + ACTUAL rates", count);
        return byCode;
    }

    private ResourceRate buildRate(UUID resourceId, String type, BigDecimal price) {
        ResourceRate rate = new ResourceRate();
        rate.setResourceId(resourceId);
        rate.setRateType(type);
        rate.setPricePerUnit(price);
        rate.setEffectiveDate(PLANNED_START);
        return rate;
    }

    private void seedManpowerResourceRoles() {
        int count = 0;
        ResourceType laborType = resourceFactory.requireType("LABOR");
        for (ResourceDef r : MANPOWER) {
            String code = "OD-ROLE-" + slug(r.description());
            if (resourceRoleRepository.findByCode(code).isPresent()) continue;
            ResourceRole role = ResourceRole.builder()
                    .code(code)
                    .name(r.description())
                    .description("Manpower role for OWD SH-10 — " + r.description())
                    .resourceType(laborType)
                    .productivityUnit(r.unit())
                    .sortOrder(0)
                    .active(true)
                    .build();
            resourceRoleRepository.save(role);
            count++;
        }
        log.info("[OD-SH10] seeded {} manpower ResourceRoles", count);
    }

    // ───────────────────────── Activities ──────────────────────
    /**
     * One activity per BOQ item. Activity codes mirror BOQ itemNo (ACT-1.1, etc.) so the SQL
     * report bundle can reference them by natural key. Schedule positions are derived from
     * package start offsets + BOQ row order. Percent-complete reflects WBS package targets.
     */
    private void seedActivities(UUID projectId, UUID calendarId, Map<String, UUID> wbs) {
        Map<String, Integer> prefixOffsetDays = Map.of(
                "1", 0,    // Earthwork: month 0
                "2", 90,   // Sub-base: month 3
                "3", 270,  // Bituminous: month 9
                "4", 60,   // Drainage: month 2
                "5", 480,  // Road furniture: month 16 (just started)
                "6", 30,   // Structures: month 1
                "7", 600   // Misc: month 20
        );
        // Map percent-complete by BOQ item — derived from packaged % with mild row variance.
        int sortOrder = 0;
        for (BoqRowDef b : BOQ_ROWS) {
            String prefix = b.itemNo().substring(0, 1);
            UUID wbsNodeId = wbs.get(prefix);
            if (wbsNodeId == null) continue;
            long durationDays = estimateDurationDays(b);
            int withinGroupIdx = withinGroupIndex(b.itemNo());
            int groupOffset = prefixOffsetDays.getOrDefault(prefix, 0);
            LocalDate start = PLANNED_START.plusDays(groupOffset + withinGroupIdx * 25L);
            LocalDate finish = start.plusDays(durationDays);

            double pct = packagePercent(prefix);
            ActivityStatus status = pct >= 100 ? ActivityStatus.COMPLETED
                    : (pct > 0 ? ActivityStatus.IN_PROGRESS : ActivityStatus.NOT_STARTED);

            Activity a = new Activity();
            a.setCode("ACT-" + b.itemNo());
            a.setName(truncate(b.description(), 100));
            a.setDescription(b.description());
            a.setProjectId(projectId);
            a.setWbsNodeId(wbsNodeId);
            a.setActivityType(ActivityType.TASK_DEPENDENT);
            a.setDurationType(DurationType.FIXED_DURATION_AND_UNITS);
            a.setStatus(status);
            a.setOriginalDuration((double) durationDays);
            a.setRemainingDuration(status == ActivityStatus.COMPLETED ? 0 : (double) durationDays * (1 - pct / 100.0));
            a.setAtCompletionDuration((double) durationDays);
            a.setPlannedStartDate(start);
            a.setPlannedFinishDate(finish);
            a.setEarlyStartDate(start);
            a.setEarlyFinishDate(finish);
            if (status != ActivityStatus.NOT_STARTED) a.setActualStartDate(start);
            if (status == ActivityStatus.COMPLETED) a.setActualFinishDate(finish);
            a.setCalendarId(calendarId);
            a.setPercentComplete(pct);
            a.setPhysicalPercentComplete(pct);
            // Mark DBM/BC as critical-path activities (the EVM pinch-points)
            a.setIsCritical(b.itemNo().equals("3.1") || b.itemNo().equals("3.2"));
            a.setSortOrder(sortOrder++);
            a.setChainageFromM(CHAINAGE_START_M);
            a.setChainageToM(CHAINAGE_END_M);
            activityRepository.save(a);
        }
        log.info("[OD-SH10] seeded {} activities", BOQ_ROWS.size());
        seedActivityRelationships(projectId);
    }

    private void seedActivityRelationships(UUID projectId) {
        if (activityRelationshipRepository.findByProjectId(projectId).size() >= 2) return;
        List<Activity> activities = activityRepository.findByProjectId(projectId).stream()
                .filter(a -> a.getCode() != null && a.getCode().startsWith("ACT-"))
                .sorted(Comparator.comparing(this::numericItemKey))
                .toList();
        if (activities.size() < 2) return;
        LinkedHashMap<String, List<Activity>> byPrefix = new LinkedHashMap<>();
        for (Activity a : activities) {
            String itemNo = a.getCode().substring("ACT-".length());
            String prefix = itemNo.substring(0, 1);
            byPrefix.computeIfAbsent(prefix, p -> new ArrayList<>()).add(a);
        }
        int created = 0;
        Activity prevTail = null;
        for (var e : byPrefix.entrySet()) {
            List<Activity> grp = e.getValue();
            for (int i = 1; i < grp.size(); i++) if (persistRel(projectId, grp.get(i - 1), grp.get(i))) created++;
            if (prevTail != null && !grp.isEmpty() && persistRel(projectId, prevTail, grp.get(0))) created++;
            if (!grp.isEmpty()) prevTail = grp.get(grp.size() - 1);
        }
        log.info("[OD-SH10] seeded {} activity relationships", created);
    }

    private boolean persistRel(UUID projectId, Activity pred, Activity succ) {
        if (activityRelationshipRepository.existsByPredecessorActivityIdAndSuccessorActivityId(pred.getId(), succ.getId())) return false;
        ActivityRelationship rel = new ActivityRelationship();
        rel.setProjectId(projectId);
        rel.setPredecessorActivityId(pred.getId());
        rel.setSuccessorActivityId(succ.getId());
        rel.setRelationshipType(RelationshipType.FINISH_TO_START);
        rel.setLag(0.0);
        rel.setIsExternal(false);
        activityRelationshipRepository.save(rel);
        return true;
    }

    private long estimateDurationDays(BoqRowDef b) {
        // Crude per-item duration: scale by quantity, clamp to [21, 240] days.
        if (b.qty() == 0) return 30;
        double dailyOutput = switch (b.itemNo().substring(0, 1)) {
            case "1" -> 1500;
            case "2" -> 600;
            case "3" -> 250;
            case "4" -> 80;
            case "5" -> 200;
            case "6" -> 30;
            case "7" -> 1;
            default -> 100;
        };
        long days = Math.max(21, Math.min(240, (long) Math.ceil(b.qty() / dailyOutput)));
        return days;
    }

    private double packagePercent(String prefix) {
        // Use WBS package summary percent — slight randomisation per row would be nice but
        // would also drift the EVM, so keep it identical.
        for (WbsPackage p : WBS_PACKAGES) if (p.groupKey().equals(prefix)) return p.percentComplete();
        return 0;
    }

    private int withinGroupIndex(String itemNo) {
        String prefix = itemNo.substring(0, 1);
        List<String> sib = BOQ_ROWS.stream().map(BoqRowDef::itemNo).filter(s -> s.startsWith(prefix)).sorted().toList();
        return Math.max(0, sib.indexOf(itemNo));
    }

    private String numericItemKey(Activity a) {
        String itemNo = a.getCode().substring("ACT-".length());
        StringBuilder key = new StringBuilder();
        for (String seg : itemNo.split("\\.")) {
            try { key.append(String.format("%08d", Integer.parseInt(seg))); }
            catch (NumberFormatException e) { key.append(seg); }
            key.append('.');
        }
        return key.toString();
    }

    // ───────────────────── Daily Progress Reports ─────────────
    /** ~120 DPRs spanning 2024-09 → 2026-04 (every 5th day). */
    private void seedDailyProgressReports(UUID projectId, Map<String, UUID> wbs) {
        String[] activities = {
                "Earthwork — Excavation", "Earthwork — Embankment", "GSB Layer", "WMM Layer",
                "DBM Layer", "BC Layer", "Box Culvert RCC", "Bridge Pier P1 Casting",
                "Bridge Superstructure — Span 1", "Bridge Pier P2 Casting"
        };
        String[] units = {"cum", "cum", "cum", "cum", "cum", "cum", "cum", "cum", "cum", "cum"};
        String[] supervisors = {"S. Behera", "P. Mishra", "R. Patnaik", "B. Mohanty", "K. Sahoo"};
        String[] remarksRotation = {
                "Smooth shift, productivity above target.",
                "Mid-day break extended by 30 min due to heat.",
                "Mason shortage delayed start by 1 hour.",
                "Equipment Roller-2 breakdown — under repair.",
                "PMC inspection passed without observations."
        };
        Map<String, BigDecimal> cum = new HashMap<>();
        List<DailyProgressReport> saved = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 9, 1);
        LocalDate end = LocalDate.of(2026, 4, 20);
        int idx = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(5)) {
            String act = activities[idx % activities.length];
            String unit = units[idx % units.length];
            BigDecimal qty = BigDecimal.valueOf(150 + (idx * 23) % 800);
            BigDecimal cumulative = cum.getOrDefault(act, BigDecimal.ZERO).add(qty);
            cum.put(act, cumulative);
            String wbsKey = wbsKeyForActivity(act);
            DailyProgressReport dpr = DailyProgressReport.builder()
                    .projectId(projectId)
                    .reportDate(d)
                    .supervisorName(supervisors[idx % supervisors.length])
                    .chainageFromM(CHAINAGE_START_M + (idx * 280L) % CHAINAGE_END_M)
                    .chainageToM(CHAINAGE_START_M + ((idx * 280L) + 800L) % CHAINAGE_END_M)
                    .activityName(act)
                    .unit(unit)
                    .qtyExecuted(qty)
                    // cumulativeQty is computed on read — no longer stored.
                    .boqItemNo(boqForActivity(act))
                    .wbsNodeId(wbsKey != null ? wbs.get(wbsKey) : null)
                    .remarks(remarksRotation[idx % remarksRotation.length])
                    .approvalStatus(DprApprovalStatus.APPROVED)
                    .build();
            saved.add(dpr);
            idx++;
        }
        dprRepository.saveAll(saved);
        log.info("[OD-SH10] seeded {} DPRs", saved.size());
    }

    private String wbsKeyForActivity(String activity) {
        String a = activity.toLowerCase();
        if (a.contains("earthwork") || a.contains("embankment")) return "1";
        if (a.contains("gsb") || a.contains("wmm")) return "2";
        if (a.contains("dbm") || a.contains("bc ")) return "3";
        if (a.contains("culvert") || a.contains("drain")) return "4";
        if (a.contains("bridge") || a.contains("rob")) return "6";
        return null;
    }

    private String boqForActivity(String activity) {
        String a = activity.toLowerCase();
        if (a.contains("earthwork") && a.contains("excavation")) return "1.1";
        if (a.contains("embankment")) return "1.2";
        if (a.contains("gsb")) return "2.1";
        if (a.contains("wmm")) return "2.2";
        if (a.contains("dbm")) return "3.1";
        if (a.contains("bc ")) return "3.2";
        if (a.contains("culvert")) return "4.2";
        if (a.contains("pier") && a.contains("p1")) return "6.1";
        if (a.contains("pier") && a.contains("p2")) return "6.1";
        if (a.contains("superstructure")) return "6.2";
        return null;
    }

    // ─────────────────── Resource Deployments ────────────────
    /** ~120 deployment rows paired with DPR dates. */
    private void seedResourceDeployments(UUID projectId) {
        com.bipros.project.domain.model.DeploymentResourceType[] types = {
                com.bipros.project.domain.model.DeploymentResourceType.EQUIPMENT,
                com.bipros.project.domain.model.DeploymentResourceType.MANPOWER
        };
        String[] descs = {
                "Excavator JCB 220", "Tipper TATA 2518", "Motor Grader CAT 120K", "Tandem Roller 10T",
                "Sensor Paver Vögele", "Hot Mix Plant", "Concrete Mixer", "Skilled Labour",
                "Unskilled Labour", "Site Engineer"
        };
        List<DailyResourceDeployment> saved = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 9, 1);
        LocalDate end = LocalDate.of(2026, 4, 20);
        int idx = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(5)) {
            int planned = 4 + (idx % 6);
            int deployed = Math.max(1, planned - (idx % 3 == 0 ? 1 : 0));
            saved.add(DailyResourceDeployment.builder()
                    .projectId(projectId)
                    .logDate(d)
                    .resourceType(types[idx % types.length])
                    .resourceDescription(descs[idx % descs.length])
                    .nosPlanned(planned)
                    .nosDeployed(deployed)
                    .hoursWorked(8.0 + (idx % 3) * 0.5)
                    .idleHours((idx % 4) * 0.25)
                    .remarks(idx % 7 == 0 ? "Equipment AMC visit." : null)
                    .build());
            idx++;
        }
        deploymentRepository.saveAll(saved);
        log.info("[OD-SH10] seeded {} resource deployments", saved.size());
    }

    // ────────────────────── Daily Weather ─────────────────────
    /**
     * ~120 weather rows. Odisha is coastal — heavy SW monsoon June-Sep, cyclone risk Oct-Nov,
     * mild winters Dec-Feb, hot dry March-May. Cyclone Dana hit Oct 2024.
     */
    private void seedDailyWeather(UUID projectId) {
        List<DailyWeather> saved = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 9, 1);
        LocalDate end = LocalDate.of(2026, 4, 20);
        int idx = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(5)) {
            int month = d.getMonthValue();
            double tmax, tmin, rain, wind;
            String cond;
            if (month >= 6 && month <= 9) {
                tmax = 30 + (idx % 4);
                tmin = 25 + (idx % 3);
                rain = 35 + (idx % 80);
                wind = 18 + (idx % 12);
                cond = idx % 3 == 0 ? "Heavy rain" : "Moderate rain";
            } else if (month == 10 || month == 11) {
                tmax = 31 + (idx % 3);
                tmin = 22 + (idx % 4);
                rain = 5 + (idx % 30);
                wind = 22 + (idx % 25);
                cond = idx % 4 == 0 ? "Cyclonic alert" : "Cloudy";
            } else if (month == 12 || month <= 2) {
                tmax = 26 + (idx % 4);
                tmin = 14 + (idx % 4);
                rain = 0;
                wind = 8 + (idx % 6);
                cond = "Clear";
            } else {
                tmax = 38 + (idx % 5);
                tmin = 24 + (idx % 4);
                rain = idx % 6 == 0 ? 12 : 0;
                wind = 12 + (idx % 8);
                cond = idx % 5 == 0 ? "Pre-monsoon shower" : "Hot & dry";
            }
            saved.add(DailyWeather.builder()
                    .projectId(projectId)
                    .logDate(d)
                    .tempMaxC(tmax)
                    .tempMinC(tmin)
                    .rainfallMm(rain)
                    .windKmh(wind)
                    .weatherCondition(cond)
                    .workingHours((double) (rain > 50 ? 4 : (rain > 20 ? 6 : 9)))
                    .remarks(rain > 70 ? "Heavy rain — earthwork suspended." : null)
                    .build());
            idx++;
        }
        weatherRepository.saveAll(saved);
        log.info("[OD-SH10] seeded {} daily weather rows", saved.size());
    }

    // ───────────────────── Next Day Plans ─────────────────────
    private void seedNextDayPlans(UUID projectId) {
        List<NextDayPlan> saved = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 2, 25); // last 60 days
        LocalDate end = LocalDate.of(2026, 4, 25);
        String[] activities = {"BC Layer at Ch 16+800–17+200", "DBM Layer at Ch 14+200–14+800",
                "Bridge P2 deck slab pour", "Side drain RR masonry — Cuttack approach",
                "Road marking — chainage 11+000 onwards"};
        int idx = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(2)) {
            String act = activities[idx % activities.length];
            saved.add(NextDayPlan.builder()
                    .projectId(projectId)
                    .reportDate(d)
                    .nextDayActivity(act)
                    .chainageFromM(CHAINAGE_START_M + (idx * 320L) % CHAINAGE_END_M)
                    .chainageToM(CHAINAGE_START_M + ((idx * 320L) + 800L) % CHAINAGE_END_M)
                    .targetQty(BigDecimal.valueOf(180 + (idx * 25) % 600))
                    .unit("cum")
                    .concerns(idx % 3 == 0 ? "Bitumen tanker delayed — alternate sourcing arranged" : null)
                    .actionBy(idx % 3 == 0 ? "Procurement Mgr" : null)
                    .dueDate(d.plusDays(1))
                    .build());
            idx++;
        }
        nextDayPlanRepository.saveAll(saved);
        log.info("[OD-SH10] seeded {} next-day plans", saved.size());
    }

    // ───────────────────── Material Consumption ───────────────
    private void seedMaterialConsumption(UUID projectId, Map<String, UUID> resources) {
        record MatRow(String material, String unit, double opening, double received, double consumed, String issuedBy, String receivedBy) {}
        String[] materials = {"Bitumen VG-30", "Cement OPC-43", "GSB aggregate 53-1.18 mm",
                "WMM aggregate 53-0.075 mm", "Crushed stone aggregate 20mm", "Crushed stone aggregate 10mm",
                "Steel TMT Fe-500D", "River sand (zone-II)"};
        String[] units = {"MT", "bag", "cum", "cum", "cum", "cum", "MT", "cum"};
        List<MaterialConsumptionLog> saved = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 9, 5);
        LocalDate end = LocalDate.of(2026, 4, 20);
        int idx = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(8)) {
            String mat = materials[idx % materials.length];
            String unit = units[idx % units.length];
            BigDecimal opening = BigDecimal.valueOf(200 + (idx * 13) % 800);
            BigDecimal received = BigDecimal.valueOf(150 + (idx * 17) % 500);
            BigDecimal consumed = BigDecimal.valueOf(180 + (idx * 11) % 480);
            BigDecimal closing = opening.add(received).subtract(consumed).max(BigDecimal.ZERO);
            saved.add(MaterialConsumptionLog.builder()
                    .projectId(projectId)
                    .logDate(d)
                    .materialName(mat)
                    .unit(unit)
                    .openingStock(opening)
                    .received(received)
                    .consumed(consumed)
                    .closingStock(closing)
                    .wastagePercent(BigDecimal.valueOf(1.0 + (idx % 5) * 0.4))
                    .issuedBy("Stores In-charge")
                    .receivedBy("Site Engineer")
                    .build());
            idx++;
        }
        materialConsumptionLogRepository.saveAll(saved);
        log.info("[OD-SH10] seeded {} material consumption rows", saved.size());
    }

    // ─────────────────────────── Helpers ──────────────────────
    private String resourceTypeCodeFor(String category) {
        return switch (category) {
            case "Manpower" -> "LABOR";
            case "Material" -> "MATERIAL";
            default -> "EQUIPMENT";
        };
    }

    /** Map legacy {@code ResourceCategory} enum names to role codes (1:1 same identifiers). */
    private String resourceRoleCodeFor(String category, String name) {
        if (name == null) return "OTHER";
        return switch (category) {
            case "Manpower" -> name.contains("Engineer") || name.contains("Surveyor")
                    ? "SITE_ENGINEER"
                    : (name.contains("Mason") ? "SKILLED_LABOUR"
                    : (name.contains("Operator") ? "OPERATOR" : "UNSKILLED_LABOUR"));
            case "Material" -> name.contains("Bitumen") ? "BITUMEN"
                    : (name.contains("Cement") ? "CEMENT"
                    : (name.contains("aggregate") || name.contains("Sand") || name.contains("WMM") || name.contains("GSB")
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
        return switch (label.toLowerCase()) {
            case "cum" -> "CU_M";
            case "mt", "bag" -> "MT";
            case "rm" -> "RMT";
            case "each" -> "NOS";
            case "lump" -> "NOS";
            case "l" -> "LITRE";
            case "kg" -> "KG";
            case "sqm" -> "NOS";
            default -> "PER_DAY";
        };
    }

    private double ratePerHourFrom(double price, String unit) {
        if (unit == null) return price;
        return switch (unit.toLowerCase()) {
            case "hour" -> price;
            case "day" -> price / 8.0;
            default -> price;
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

    private String slug(String s) {
        if (s == null) return "UNKNOWN";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toUpperCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
            if (sb.length() >= 40) break;
        }
        return sb.length() == 0 ? "UNKNOWN" : sb.toString();
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
