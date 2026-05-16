package com.bipros.api.config.seeder;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.util.IOUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the customer-supplied OMAN-Demo workbooks copied into
 * {@code seed-data/oman-demo/} from {@code docs/ActualData/}. Modelled on
 * {@link KhasabDailyDataWorkbookReader} — same POI-based approach, same typed-record
 * style, scoped to the new oman-demo classpath path so the existing SC-180 seeders are
 * untouched.
 *
 * <p>Workbooks consumed (all classpath-relative):
 * <ul>
 *   <li>{@code daily-data-khasab.xlsx} — sheets {@code Jan-2026 / Feb-2026 / March-2026}
 *       (denormalised daily rows) and {@code Code} (BOQ activity master).</li>
 *   <li>{@code supervisor-engineer-cm-pm-dbs.xlsx} — sheet {@code MP &amp; Eqpt Summary}
 *       (CM heads as column headers, equipment deployment cells).</li>
 *   <li>{@code capacity-utilization.xlsx} — sheets {@code Plant utilization} and
 *       {@code Manpower utilization}.</li>
 *   <li>{@code dpr-internal.xlsx} — sheet {@code Summary} (BOQ projection vs achievement).</li>
 *   <li>{@code sc180-performance-2024-10-31.xlsx / 2024-11-30.xlsx / 2025-01-05.xlsx} —
 *       three historical performance snapshots; the snapshot date is derived from the
 *       filename.</li>
 *   <li>{@code concrete-summary-khasab.xlsx / concrete-summary-lima.xlsx} — concrete pour
 *       registers (same shape as the existing SC-180 concrete summaries).</li>
 * </ul>
 *
 * <p>Every reader is best-effort: if a workbook or sheet is missing the method returns
 * an empty collection instead of throwing, so partial seeds and absent classpath
 * resources don't break boot.
 */
@Slf4j
@Component
public class OmanDemoWorkbookReader {

    static {
        // The customer's capacity-utilization workbook compresses extremely well (lots
        // of repeated #REF! cells), which trips POI's default 0.01 inflation-ratio
        // zip-bomb guard. Relax it together with the byte-array and max-text limits —
        // these are trusted local-classpath files, not user uploads.
        ZipSecureFile.setMinInflateRatio(0.0);
        ZipSecureFile.setMaxTextSize(1024L * 1024L * 1024L);     // 1 GB
        ZipSecureFile.setMaxEntrySize(1024L * 1024L * 1024L);    // 1 GB
        IOUtils.setByteArrayMaxOverride(256 * 1024 * 1024);      // 256 MB
    }

    public static final String DAILY_PATH = "seed-data/oman-demo/daily-data-khasab.xlsx";
    public static final String SUPERVISOR_DBS_PATH = "seed-data/oman-demo/supervisor-engineer-cm-pm-dbs.xlsx";
    public static final String CAPACITY_PATH = "seed-data/oman-demo/capacity-utilization.xlsx";
    public static final String DPR_TEMPLATE_PATH = "seed-data/oman-demo/dpr-internal.xlsx";
    public static final String CONCRETE_KHASAB_PATH = "seed-data/oman-demo/concrete-summary-khasab.xlsx";
    public static final String CONCRETE_LIMA_PATH = "seed-data/oman-demo/concrete-summary-lima.xlsx";

    /**
     * Three SC180 performance snapshots in chronological order. Filenames preserve the
     * original Oct-24 / Nov-24 / Jan-25 capture dates; the {@code date} field is shifted
     * +1 year so the snapshot timeline aligns with the daily-data workbook (which is
     * also shifted +1 year in {@link OmanDemoDailyDataSeeder} — see its {@code YEAR_SHIFT}
     * field doc for the rationale).
     */
    public static final List<PerformanceFile> PERFORMANCE_FILES = List.of(
            new PerformanceFile("seed-data/oman-demo/sc180-performance-2024-10-31.xlsx",
                    LocalDate.of(2025, 10, 31)),
            new PerformanceFile("seed-data/oman-demo/sc180-performance-2024-11-30.xlsx",
                    LocalDate.of(2025, 11, 30)),
            new PerformanceFile("seed-data/oman-demo/sc180-performance-2025-01-05.xlsx",
                    LocalDate.of(2026, 1, 5))
    );

    private static final DataFormatter FORMATTER = new DataFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH)
    };

    public boolean dailyDataAvailable() {
        return new ClassPathResource(DAILY_PATH).exists();
    }

    public boolean supervisorMasterAvailable() {
        return new ClassPathResource(SUPERVISOR_DBS_PATH).exists();
    }

    public boolean capacityAvailable() {
        return new ClassPathResource(CAPACITY_PATH).exists();
    }

    public boolean dprTemplateAvailable() {
        return new ClassPathResource(DPR_TEMPLATE_PATH).exists();
    }

    public boolean performanceAvailable() {
        for (PerformanceFile pf : PERFORMANCE_FILES) {
            if (new ClassPathResource(pf.path()).exists()) return true;
        }
        return false;
    }

    // ───────────────────────── Record types ─────────────────────────

    /** Master BOQ activity from the daily-data {@code Code} sheet. */
    public record ActivityCodeRow(String code, String description, String unit) {}

    /**
     * Denormalised daily row identical in shape to
     * {@link KhasabDailyDataWorkbookReader.DailyDataRawRow} so the daily-data seeder logic
     * carries over directly.
     */
    public record DailyDataRawRow(
            LocalDate date,
            String site,
            String location,
            Long chainageFromM,
            Long chainageToM,
            String activityCode,
            String unit,
            BigDecimal executedQty,
            String supervisorName,
            String manpowerTrade,
            Integer manpowerNos,
            BigDecimal manpowerHours,
            BigDecimal manpowerRate,
            BigDecimal manpowerCost,
            String equipmentType,
            Integer equipmentNos,
            BigDecimal equipmentHours,
            BigDecimal equipmentRate,
            BigDecimal equipmentCost,
            String materialDescription,
            String materialUnit,
            BigDecimal materialQty,
            BigDecimal materialRate,
            BigDecimal materialCost,
            String remarks
    ) {}

    /**
     * Staff master row, synthesised from the daily-data manpower names + the supervisor
     * master sheet's CM column headers. {@code roleCategory} is one of
     * {@code SUPERVISOR}, {@code ENGINEER}, {@code CM}, {@code PM}.
     */
    public record StaffMasterRow(
            String fullName,
            String roleCategory,
            String designation,
            String department
    ) {}

    /** One row from {@code Plant utilization} or {@code Manpower utilization}. */
    public record CapacityRow(
            String resourceType,        // "EQUIPMENT" or "MANPOWER"
            String description,
            String unit,
            BigDecimal budgetForDay,
            BigDecimal actualForDay,
            BigDecimal budgetedDays,
            BigDecimal actualDays,
            BigDecimal utilizationPct
    ) {}

    /** One row from the SC180 performance {@code Summary} sheet. */
    public record PerformanceSnapshotRow(
            LocalDate snapshotDate,
            String trade,
            BigDecimal mmRate,
            BigDecimal budgetedMandays,
            BigDecimal actualMandays,
            BigDecimal budgetedNos,
            BigDecimal actualNos,
            BigDecimal utilizationPct,
            BigDecimal costImplication
    ) {}

    /** One row from {@code dpr-internal.xlsx} {@code Summary} sheet. */
    public record DprTemplateRow(
            String boqNo,
            String activityName,
            String unit,
            BigDecimal unitRate,
            BigDecimal monthlyPlanQty,
            BigDecimal cumulativeQty
    ) {}

    /** Performance file → snapshot date. */
    public record PerformanceFile(String path, LocalDate date) {}

    /** Concrete pour row, shared shape with KhasabDailyDataWorkbookReader.ConcretePourRow. */
    public record ConcretePourRow(
            LocalDate pourDate,
            String site,
            String plantName,
            Long chainageM,
            String structure,
            String element,
            String gradeCode,
            BigDecimal quantityM3,
            BigDecimal slump,
            BigDecimal temperature,
            String section
    ) {}

    // ───────────────────────── Public readers ─────────────────────────

    /** Read all activity codes from the {@code Code} sheet of daily-data-khasab.xlsx. */
    public List<ActivityCodeRow> readActivityCodes() {
        if (!dailyDataAvailable()) return List.of();
        return openWorkbook(DAILY_PATH, wb -> {
            Sheet s = wb.getSheet("Code");
            if (s == null) return List.of();
            List<ActivityCodeRow> out = new ArrayList<>();
            for (int i = 1; i <= s.getLastRowNum(); i++) {
                Row r = s.getRow(i);
                if (r == null) continue;
                String code = stringValue(r.getCell(2));
                String desc = stringValue(r.getCell(3));
                String unit = stringValue(r.getCell(4));
                if (code == null || desc == null) continue;
                out.add(new ActivityCodeRow(code, desc, unit));
            }
            return out;
        });
    }

    /** Read every daily-data row across Jan/Feb/March sheets. */
    public List<DailyDataRawRow> readAllDailyRows() {
        if (!dailyDataAvailable()) return List.of();
        return openWorkbook(DAILY_PATH, wb -> {
            List<DailyDataRawRow> out = new ArrayList<>();
            for (String sheetName : List.of("Jan-2026", "Feb-2026", "March-2026")) {
                Sheet s = wb.getSheet(sheetName);
                if (s == null) {
                    log.warn("[oman-demo reader] sheet '{}' missing", sheetName);
                    continue;
                }
                for (int i = 4; i <= s.getLastRowNum(); i++) {
                    Row r = s.getRow(i);
                    if (r == null) continue;
                    DailyDataRawRow row = parseDailyRow(r);
                    if (row == null) continue;
                    out.add(row);
                }
            }
            return out;
        });
    }

    /**
     * Build the staff master by combining (a) supervisor names harvested from the daily
     * data and (b) the CM/PM column headers on the supervisor-master sheet. Engineers
     * and PM are synthesised when no real-data anchor exists so the demo project always
     * has a full PM/CM/engineer/supervisor hierarchy.
     */
    public List<StaffMasterRow> readStaffMaster() {
        Set<String> supervisorNames = new LinkedHashSet<>();
        if (dailyDataAvailable()) {
            for (DailyDataRawRow r : readAllDailyRows()) {
                if (r.supervisorName() != null && !r.supervisorName().isBlank()) {
                    supervisorNames.add(r.supervisorName().trim());
                }
            }
        }

        // CM heads come from the supervisor-master sheet column headers (T Swamy, etc.).
        // Pull cell row 2 (index 1) columns 8..11 in MP & Eqpt Summary.
        Set<String> cmNames = new LinkedHashSet<>();
        if (supervisorMasterAvailable()) {
            cmNames.addAll(openWorkbook(SUPERVISOR_DBS_PATH, wb -> {
                Sheet s = wb.getSheet("MP & Eqpt Summary");
                if (s == null) return Set.<String>of();
                Set<String> out = new LinkedHashSet<>();
                Row header = s.getRow(1);
                if (header == null) return out;
                for (int col = 8; col <= 11; col++) {
                    String v = stringValue(header.getCell(col));
                    if (v != null && !v.equalsIgnoreCase("Off Road")
                            && !v.equalsIgnoreCase("Total")
                            && !v.equalsIgnoreCase("Available @ site")) {
                        out.add(v);
                    }
                }
                return out;
            }));
        }

        // Build the final list in a deterministic order: PM, CMs, engineers, supervisors.
        List<StaffMasterRow> out = new ArrayList<>();

        // One PM for the project (synthetic — no name in the workbooks).
        out.add(new StaffMasterRow("R. Subramanian", "PM",
                "Project Manager — Khasab–Daba", "CIVIL"));

        // CM heads from the supervisor master (typically 2–3 names).
        int idx = 1;
        for (String cm : cmNames) {
            out.add(new StaffMasterRow(cm, "CM",
                    "Construction Manager " + (idx++), "CIVIL"));
        }
        // Always include at least one CM even when the master sheet is unreadable.
        if (cmNames.isEmpty()) {
            out.add(new StaffMasterRow("T Swamy", "CM",
                    "Construction Manager 1", "CIVIL"));
            out.add(new StaffMasterRow("A K Singh", "CM",
                    "Construction Manager 2", "CIVIL"));
        }

        // Site engineers (synthetic, mirror typical Oman highway team).
        out.add(new StaffMasterRow("M. Pradeep", "ENGINEER",
                "Senior Site Engineer", "CIVIL"));
        out.add(new StaffMasterRow("S. Ramesh", "ENGINEER",
                "Site Engineer — Earthworks", "CIVIL"));
        out.add(new StaffMasterRow("Khalid Al Balushi", "ENGINEER",
                "Site Engineer — Pavement", "CIVIL"));
        out.add(new StaffMasterRow("Sundar Raj", "ENGINEER",
                "QA / QC Engineer", "QUALITY"));

        // Supervisors come from the real daily data.
        for (String sup : supervisorNames) {
            out.add(new StaffMasterRow(sup, "SUPERVISOR", "Site Supervisor", "CIVIL"));
        }
        // Fallback if daily data wasn't readable: hardcoded canonical names from prior runs.
        if (supervisorNames.isEmpty()) {
            for (String sup : List.of("K. Barman", "Sohail", "Illayaraja", "Parvaiz",
                    "Manzar", "Mohd Ismaila", "Vijaykumar", "Md Saiffuddin",
                    "V.P. Gupta", "A.K. Mishra", "Sanjar Alam", "Anirban Datta")) {
                out.add(new StaffMasterRow(sup, "SUPERVISOR", "Site Supervisor", "CIVIL"));
            }
        }
        return out;
    }

    /** Read both capacity-utilization sheets (plant + manpower). */
    public List<CapacityRow> readCapacityRows() {
        if (!capacityAvailable()) return List.of();
        // Re-assert the relaxed zip-bomb thresholds at call time. Other beans may have
        // touched POI before my static initializer ran and the defaults can creep back
        // in via thread-local or per-stream caches in some POI versions.
        ZipSecureFile.setMinInflateRatio(-1.0);
        ZipSecureFile.setMaxTextSize(1024L * 1024L * 1024L);
        ZipSecureFile.setMaxEntrySize(1024L * 1024L * 1024L);
        IOUtils.setByteArrayMaxOverride(256 * 1024 * 1024);
        return openWorkbook(CAPACITY_PATH, wb -> {
            List<CapacityRow> out = new ArrayList<>();
            for (Map.Entry<String, String> e : Map.of(
                    "Plant utilization", "EQUIPMENT",
                    "Manpower utilization", "MANPOWER").entrySet()) {
                Sheet s = wb.getSheet(e.getKey());
                if (s == null) continue;
                // Data rows typically start at index 4; loop generously.
                for (int i = 3; i <= s.getLastRowNum(); i++) {
                    Row r = s.getRow(i);
                    if (r == null) continue;
                    String desc = stringValue(r.getCell(1));
                    if (desc == null || desc.equalsIgnoreCase("Description")) continue;
                    String unit = stringValue(r.getCell(2));
                    BigDecimal budDay = decimalValue(r.getCell(3));
                    BigDecimal actDay = decimalValue(r.getCell(5));
                    BigDecimal budDays = decimalValue(r.getCell(7));
                    BigDecimal actDays = decimalValue(r.getCell(8));
                    BigDecimal util = decimalValue(r.getCell(9));
                    // Skip empty/separator rows.
                    if (budDay == null && actDay == null && budDays == null
                            && actDays == null && util == null) continue;
                    out.add(new CapacityRow(e.getValue(), desc, unit,
                            budDay, actDay, budDays, actDays, util));
                }
            }
            return out;
        });
    }

    /** Read DPR projection-vs-achieved rows from the dpr-internal Summary sheet. */
    public List<DprTemplateRow> readDprTemplateRows() {
        if (!dprTemplateAvailable()) return List.of();
        return openWorkbook(DPR_TEMPLATE_PATH, wb -> {
            Sheet s = wb.getSheet("Summary");
            if (s == null) return List.<DprTemplateRow>of();
            List<DprTemplateRow> out = new ArrayList<>();
            // Header is around row 10; scan generously and skip rows where boqNo is non-numeric/blank.
            for (int i = 11; i <= s.getLastRowNum(); i++) {
                Row r = s.getRow(i);
                if (r == null) continue;
                String boq = stringValue(r.getCell(1));
                String name = stringValue(r.getCell(2));
                if (boq == null || name == null) continue;
                String unit = stringValue(r.getCell(3));
                BigDecimal rate = decimalValue(r.getCell(4));
                BigDecimal monthlyQty = decimalValue(r.getCell(5));
                BigDecimal cumQty = decimalValue(r.getCell(9));
                out.add(new DprTemplateRow(boq, name, unit, rate, monthlyQty, cumQty));
            }
            return out;
        });
    }

    /** Read performance snapshot rows merged across the 3 SC180 files. */
    public List<PerformanceSnapshotRow> readPerformanceSnapshots() {
        List<PerformanceSnapshotRow> out = new ArrayList<>();
        for (PerformanceFile pf : PERFORMANCE_FILES) {
            if (!new ClassPathResource(pf.path()).exists()) continue;
            out.addAll(openWorkbook(pf.path(), wb -> {
                Sheet s = wb.getSheet("Summary");
                if (s == null) return List.<PerformanceSnapshotRow>of();
                List<PerformanceSnapshotRow> rows = new ArrayList<>();
                for (int i = 5; i <= s.getLastRowNum(); i++) {
                    Row r = s.getRow(i);
                    if (r == null) continue;
                    String trade = stringValue(r.getCell(1));
                    if (trade == null || trade.equalsIgnoreCase("Trade")) continue;
                    BigDecimal rate = decimalValue(r.getCell(2));
                    BigDecimal budMandays = decimalValue(r.getCell(3));
                    BigDecimal actMandays = decimalValue(r.getCell(4));
                    BigDecimal budNos = decimalValue(r.getCell(5));
                    BigDecimal actNos = decimalValue(r.getCell(6));
                    BigDecimal util = decimalValue(r.getCell(7));
                    BigDecimal cost = decimalValue(r.getCell(8));
                    if (budMandays == null && actMandays == null && rate == null) continue;
                    rows.add(new PerformanceSnapshotRow(pf.date(), trade, rate,
                            budMandays, actMandays, budNos, actNos, util, cost));
                }
                return rows;
            }));
        }
        return out;
    }

    /** Read concrete pours from both Khasab and Lima registers. */
    public List<ConcretePourRow> readConcretePours() {
        List<ConcretePourRow> out = new ArrayList<>();
        if (new ClassPathResource(CONCRETE_KHASAB_PATH).exists()) {
            out.addAll(openWorkbook(CONCRETE_KHASAB_PATH, wb -> parseConcrete(wb, "Khasab", false)));
        }
        if (new ClassPathResource(CONCRETE_LIMA_PATH).exists()) {
            out.addAll(openWorkbook(CONCRETE_LIMA_PATH, wb -> parseConcrete(wb, "Lima", true)));
        }
        return out;
    }

    // ───────────────────────── Internals ─────────────────────────

    private List<ConcretePourRow> parseConcrete(Workbook wb, String site, boolean withSlumpTemp) {
        Sheet s = wb.getSheet(site);
        if (s == null) return List.of();
        List<ConcretePourRow> out = new ArrayList<>();
        for (int i = 3; i <= s.getLastRowNum(); i++) {
            Row r = s.getRow(i);
            if (r == null) continue;
            LocalDate date = cellToDate(r.getCell(2));
            if (date == null) continue;
            Long chainage = parseLongChainage(r.getCell(3));
            String structure = stringValue(r.getCell(4));
            String element = stringValue(r.getCell(5));
            String grade = stringValue(r.getCell(6));
            BigDecimal qty = decimalValue(r.getCell(7));
            BigDecimal slump = withSlumpTemp ? decimalValue(r.getCell(8)) : null;
            BigDecimal temp = withSlumpTemp ? decimalValue(r.getCell(9)) : null;
            String plant = stringValue(r.getCell(withSlumpTemp ? 10 : 8));
            String section = stringValue(r.getCell(withSlumpTemp ? 11 : 9));
            if (qty == null || structure == null) continue;
            out.add(new ConcretePourRow(date, site, plant, chainage, structure, element,
                    grade, qty, slump, temp, section));
        }
        return out;
    }

    private DailyDataRawRow parseDailyRow(Row r) {
        LocalDate date = cellToDate(r.getCell(1));
        if (date == null) return null;
        String site = stringValue(r.getCell(2));
        String location = stringValue(r.getCell(3));
        Long from = parseLongChainage(r.getCell(4));
        Long to = parseLongChainage(r.getCell(5));
        String activityCode = stringValue(r.getCell(7));
        String unit = stringValue(r.getCell(8));
        BigDecimal qty = decimalValue(r.getCell(9));
        String supervisor = stringValue(r.getCell(10));

        String mpTrade = stringValue(r.getCell(11));
        Integer mpNos = intValue(r.getCell(12));
        BigDecimal mpHours = decimalValue(r.getCell(13));
        BigDecimal mpRate = decimalValue(r.getCell(14));
        BigDecimal mpCost = decimalValue(r.getCell(15));

        String eqType = stringValue(r.getCell(16));
        Integer eqNos = intValue(r.getCell(17));
        BigDecimal eqHours = decimalValue(r.getCell(18));
        BigDecimal eqRate = decimalValue(r.getCell(19));
        BigDecimal eqCost = decimalValue(r.getCell(20));

        String matDesc = stringValue(r.getCell(21));
        String matUnit = stringValue(r.getCell(22));
        BigDecimal matQty = decimalValue(r.getCell(23));
        BigDecimal matRate = decimalValue(r.getCell(24));
        BigDecimal matCost = decimalValue(r.getCell(25));

        String remarks = stringValue(r.getCell(32));

        if (supervisor == null && activityCode == null && mpTrade == null
                && eqType == null && matDesc == null) {
            return null;
        }
        return new DailyDataRawRow(
                date, site, location, from, to, activityCode, unit, qty, supervisor,
                mpTrade, mpNos, mpHours, mpRate, mpCost,
                eqType, eqNos, eqHours, eqRate, eqCost,
                matDesc, matUnit, matQty, matRate, matCost,
                remarks
        );
    }

    @FunctionalInterface
    private interface WorkbookFn<T> {
        T accept(Workbook wb) throws Exception;
    }

    private <T> T openWorkbook(String path, WorkbookFn<T> fn) {
        ClassPathResource res = new ClassPathResource(path);
        if (!res.exists()) {
            throw new IllegalStateException("Workbook not on classpath: " + path);
        }
        try (InputStream is = res.getInputStream();
             Workbook wb = new XSSFWorkbook(is)) {
            return fn.accept(wb);
        } catch (Exception e) {
            throw new RuntimeException("Failed reading " + path + ": " + e.getMessage(), e);
        }
    }

    private String stringValue(Cell c) {
        if (c == null) return null;
        CellType type = c.getCellType() == CellType.FORMULA
                ? c.getCachedFormulaResultType() : c.getCellType();
        if (type == CellType.BLANK || type == CellType.ERROR) return null;
        String v = FORMATTER.formatCellValue(c).trim();
        if (v.isEmpty() || "-".equals(v) || "—".equals(v) || "#REF!".equals(v)) return null;
        return v;
    }

    private BigDecimal decimalValue(Cell c) {
        if (c == null) return null;
        CellType type = c.getCellType() == CellType.FORMULA
                ? c.getCachedFormulaResultType() : c.getCellType();
        if (type == CellType.NUMERIC) {
            return BigDecimal.valueOf(c.getNumericCellValue());
        }
        String s = stringValue(c);
        if (s == null) return null;
        try {
            return new BigDecimal(s.replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer intValue(Cell c) {
        BigDecimal v = decimalValue(c);
        return v == null ? null : v.intValue();
    }

    private Long parseLongChainage(Cell c) {
        BigDecimal v = decimalValue(c);
        return v == null ? null : v.longValue();
    }

    private LocalDate cellToDate(Cell c) {
        if (c == null) return null;
        try {
            CellType type = c.getCellType() == CellType.FORMULA
                    ? c.getCachedFormulaResultType() : c.getCellType();
            if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                Date d = c.getDateCellValue();
                if (d == null) return null;
                return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            String v = stringValue(c);
            return v == null ? null : parseDate(v);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseDate(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        int space = trimmed.indexOf(' ');
        if (space > 0) trimmed = trimmed.substring(0, space);
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(trimmed, f);
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
