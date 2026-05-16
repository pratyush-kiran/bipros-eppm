package com.bipros.api.config.seeder;

import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Locale;

/**
 * Reads the customer-supplied SC-180 (Khasab–Daba) workbooks shipped under
 * {@code seed-data/khasab/}. Replaces the previous synthetic {@code KhasabDailyDataSeeder}
 * dataset with real customer records for Jan–Mar 2025 (note: filenames say 2026 but the
 * cell dates are 2025; we honour the cells per user decision).
 *
 * <p>Files consumed:
 * <ul>
 *   <li>{@code daily-data-khasab.xlsx} — sheets {@code Jan-2026}, {@code Feb-2026},
 *       {@code March-2026} (denormalised: each row = one manpower OR equipment OR material
 *       line within a DPR), and {@code Code} (BOQ activity master).</li>
 *   <li>{@code concrete-summary-khasab.xlsx} — sheet {@code Khasab}.</li>
 *   <li>{@code concrete-summary-lima.xlsx} — sheet {@code Lima}.</li>
 *   <li>{@code sc180-performance.xlsx} — sheet {@code PR for MP and Eqt} (productivity norms).</li>
 * </ul>
 *
 * <p>The reader exposes typed records; the seeder is responsible for grouping flat rows into
 * DPR aggregates and resolving FKs (project, activity, supervisor user).
 */
@Slf4j
@Component
public class KhasabDailyDataWorkbookReader {

    public static final String DAILY_PATH = "seed-data/khasab/daily-data-khasab.xlsx";
    public static final String CONCRETE_KHASAB_PATH = "seed-data/khasab/concrete-summary-khasab.xlsx";
    public static final String CONCRETE_LIMA_PATH = "seed-data/khasab/concrete-summary-lima.xlsx";
    public static final String PERFORMANCE_PATH = "seed-data/khasab/sc180-performance.xlsx";

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

    public boolean concreteAvailable() {
        return new ClassPathResource(CONCRETE_KHASAB_PATH).exists()
                || new ClassPathResource(CONCRETE_LIMA_PATH).exists();
    }

    public boolean performanceAvailable() {
        return new ClassPathResource(PERFORMANCE_PATH).exists();
    }

    // ───────────────────────── Record types ─────────────────────────

    /** Master BOQ activity code from {@code Code} sheet (col index 2 = code, 3 = description, 4 = unit). */
    public record ActivityCodeRow(String code, String description, String unit) {}

    /**
     * A single raw row from the Jan/Feb/March daily-data sheets. The customer's workbook is
     * denormalised — multiple rows share the same (date, supervisor, activity, chainage), each
     * carrying one manpower OR equipment OR material entry. The seeder groups these into DPRs.
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
            // manpower fields (cols 11-15)
            String manpowerTrade,
            Integer manpowerNos,
            BigDecimal manpowerHours,
            BigDecimal manpowerRate,
            BigDecimal manpowerCost,
            // equipment fields (cols 16-20)
            String equipmentType,
            Integer equipmentNos,
            BigDecimal equipmentHours,
            BigDecimal equipmentRate,
            BigDecimal equipmentCost,
            // material fields (cols 21-25)
            String materialDescription,
            String materialUnit,
            BigDecimal materialQty,
            BigDecimal materialRate,
            BigDecimal materialCost,
            String remarks
    ) {}

    /** One concrete pour from Concrete Summary - Khasab.xlsx or Concrete Summary - Lima.xlsx. */
    public record ConcretePourRow(
            LocalDate pourDate,
            String site,           // "Khasab" or "Lima"
            String plantName,      // "SCC Khasab", "SCC Lima", "SCC KM47"
            Long chainageM,        // location/chainage
            String structure,      // "Box Culvert", "Concrete Barrier", "Retaining Wall"
            String element,        // sub-component label
            String gradeCode,      // "C15", "C25", "C30", "C35"
            BigDecimal quantityM3,
            BigDecimal slump,      // Lima only; nullable
            BigDecimal temperature, // Lima only; nullable
            String section
    ) {}

    /** One productivity norm row from {@code PR for MP and Eqt} sheet. */
    public record ProductivityNormRow(
            String boqCode,
            String resourceCode,   // e.g., "HLP", "EXV", "DZR"
            String resourceName,   // e.g., "Helpers", "Excavator", "Dozer"
            String unit,
            BigDecimal budgetNorm,
            BigDecimal actualNorm
    ) {}

    // ───────────────────────── Public readers ─────────────────────────

    /**
     * Stream all activity codes from the {@code Code} sheet. Returns codes with their
     * canonical description and unit per row (col 2 = code, col 3 = description, col 4 = unit).
     */
    public List<ActivityCodeRow> readActivityCodes() {
        return openDaily(wb -> {
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

    /**
     * Read every data row from Jan-2026, Feb-2026, March-2026 sheets. Rows where every key
     * column is null are filtered out. Returns ~26k rows.
     */
    public List<DailyDataRawRow> readAllDailyRows() {
        return openDaily(wb -> {
            List<DailyDataRawRow> out = new ArrayList<>();
            for (String sheetName : List.of("Jan-2026", "Feb-2026", "March-2026")) {
                Sheet s = wb.getSheet(sheetName);
                if (s == null) {
                    log.warn("[Khasab reader] sheet '{}' missing", sheetName);
                    continue;
                }
                // Header rows are 1-4; data starts at row index 4 (5th row).
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

    /** Reads Khasab + Lima concrete pours and returns them in one list. */
    public List<ConcretePourRow> readConcretePours() {
        List<ConcretePourRow> out = new ArrayList<>();

        if (new ClassPathResource(CONCRETE_KHASAB_PATH).exists()) {
            openWorkbook(CONCRETE_KHASAB_PATH, wb -> {
                Sheet s = wb.getSheet("Khasab");
                if (s == null) return null;
                // Header at row index 2; data starts at row index 3.
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
                    String plant = stringValue(r.getCell(8));
                    String section = stringValue(r.getCell(9));
                    if (qty == null || structure == null) continue;
                    out.add(new ConcretePourRow(date, "Khasab", plant, chainage,
                            structure, element, grade, qty, null, null, section));
                }
                return null;
            });
        }

        if (new ClassPathResource(CONCRETE_LIMA_PATH).exists()) {
            openWorkbook(CONCRETE_LIMA_PATH, wb -> {
                Sheet s = wb.getSheet("Lima");
                if (s == null) return null;
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
                    BigDecimal slump = decimalValue(r.getCell(8));
                    BigDecimal temp = decimalValue(r.getCell(9));
                    String plant = stringValue(r.getCell(10));
                    String section = stringValue(r.getCell(11));
                    if (qty == null || structure == null) continue;
                    out.add(new ConcretePourRow(date, "Lima", plant, chainage,
                            structure, element, grade, qty, slump, temp, section));
                }
                return null;
            });
        }

        return out;
    }

    /**
     * Reads productivity norms from the performance workbook's {@code PR for MP and Eqt} sheet.
     * The sheet is grouped: a "header" row identifies a BOQ activity (col 0 = serial number,
     * col 2 = activity name), followed by per-resource rows (col 1 = resource code, col 2 =
     * resource name, col 3 = unit, col 4 = budget norm, col 6 = actuals).
     */
    public List<ProductivityNormRow> readProductivityNorms() {
        if (!new ClassPathResource(PERFORMANCE_PATH).exists()) return List.of();
        return openWorkbook(PERFORMANCE_PATH, wb -> {
            Sheet s = wb.getSheet("PR for MP and Eqt");
            if (s == null) return List.of();
            List<ProductivityNormRow> out = new ArrayList<>();
            String currentBoq = null;
            // Data rows start at index 5 per the structure observed.
            for (int i = 5; i <= s.getLastRowNum(); i++) {
                Row r = s.getRow(i);
                if (r == null) continue;
                String serial = stringValue(r.getCell(0));
                String resourceCode = stringValue(r.getCell(1));
                String name = stringValue(r.getCell(2));
                String unit = stringValue(r.getCell(3));
                BigDecimal budget = decimalValue(r.getCell(4));
                BigDecimal actual = decimalValue(r.getCell(6));
                if (serial != null && resourceCode == null && name != null) {
                    // Activity header row.
                    currentBoq = name;
                    continue;
                }
                if (resourceCode == null || name == null) continue;
                if (currentBoq == null) continue;
                out.add(new ProductivityNormRow(currentBoq, resourceCode, name, unit, budget, actual));
            }
            return out;
        });
    }

    // ───────────────────────── Internals ─────────────────────────

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

        // Skip rows with no useful payload.
        if (supervisor == null && activityCode == null && mpTrade == null && eqType == null && matDesc == null) {
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

    private <T> T openDaily(WorkbookFn<T> fn) {
        return openWorkbook(DAILY_PATH, fn);
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
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        if (type == CellType.BLANK || type == CellType.ERROR) return null;
        String v = FORMATTER.formatCellValue(c).trim();
        if (v.isEmpty() || "-".equals(v) || "—".equals(v) || "#REF!".equals(v)) return null;
        return v;
    }

    private BigDecimal decimalValue(Cell c) {
        if (c == null) return null;
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
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
            CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
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
        // Trim trailing time component like " 00:00:00"
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
