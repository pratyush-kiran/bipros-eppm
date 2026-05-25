package com.bipros.api.config.seeder;

import com.bipros.api.config.seeder.KhasabDailyDataWorkbookReader.ActivityCodeRow;
import com.bipros.api.config.seeder.KhasabDailyDataWorkbookReader.ConcretePourRow;
import com.bipros.api.config.seeder.KhasabDailyDataWorkbookReader.DailyDataRawRow;
import com.bipros.api.config.seeder.KhasabDailyDataWorkbookReader.ProductivityNormRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Debug-friendly smoke test for {@link KhasabDailyDataWorkbookReader}.
 *
 * <p>Reads the customer-supplied Khasab workbook from the classpath
 * ({@code seed-data/khasab/daily-data-khasab.xlsx} — byte-identical to
 * {@code docs/ActualData/1. Daily Data-Khasab  Jan, Feb, Mar 2026.xlsx})
 * and exercises every public method of the reader. Each method prints row
 * counts and the first 5 parsed records so the actual output can be eyeballed
 * in the test log.
 *
 * <p>To see the printed dumps:
 * <pre>{@code
 * mvn -pl bipros-api -am test \
 *     -Dtest=KhasabDailyDataWorkbookReaderTest \
 *     -DforkCount=1 -DreuseForks=false -Dsurefire.useFile=false
 * }</pre>
 */
class KhasabDailyDataWorkbookReaderTest {

  private final KhasabDailyDataWorkbookReader reader = new KhasabDailyDataWorkbookReader();

  @Test
  void workbooks_are_on_classpath() {
    assertThat(reader.dailyDataAvailable()).as("daily-data-khasab.xlsx").isTrue();
    assertThat(reader.concreteAvailable()).as("concrete-summary-*.xlsx").isTrue();
    assertThat(reader.performanceAvailable()).as("sc180-performance.xlsx").isTrue();
  }

  @Test
  void activity_codes_are_parsed_from_Code_sheet() {
    List<ActivityCodeRow> rows = reader.readActivityCodes();
    System.out.println("[Khasab reader] activity codes: " + rows.size());
    rows.stream().limit(5).forEach(r -> System.out.println("  CODE: " + r));

    assertThat(rows).isNotEmpty();
    rows.forEach(r -> {
      assertThat(r.code()).as("code").isNotBlank();
      assertThat(r.description()).as("description").isNotBlank();
    });
  }

  @Test
  void daily_rows_cover_three_months() {
    List<DailyDataRawRow> rows = reader.readAllDailyRows();
    System.out.println("[Khasab reader] daily rows: " + rows.size());
    rows.stream().limit(5).forEach(r -> System.out.println("  DAILY: " + r));

    assertThat(rows).isNotEmpty();
    // Reader comment says ~26k rows; leave generous headroom in case the workbook is trimmed.
    assertThat(rows.size()).isGreaterThan(1_000);

    // Cell dates are 2025 per the reader's class-level note — honour the cells.
    assertThat(rows).as("at least one Jan row")
            .anyMatch(r -> r.date() != null && r.date().getMonth() == Month.JANUARY);
    assertThat(rows).as("at least one Feb row")
            .anyMatch(r -> r.date() != null && r.date().getMonth() == Month.FEBRUARY);
    assertThat(rows).as("at least one Mar row")
            .anyMatch(r -> r.date() != null && r.date().getMonth() == Month.MARCH);
  }

  @Test
  void daily_rows_grouped_by_date_activityCode_supervisor() {
    List<WideRawRow> raw = readWideRawRows();
    long mpRows = raw.stream().filter(r -> r.manpowerCategory() != null).count();
    long eqRows = raw.stream().filter(r -> r.equipmentDetail() != null).count();
    long matRows = raw.stream().filter(r -> r.materialDescription() != null).count();
    long subRows = raw.stream().filter(r -> r.subcontractorName() != null).count();
    System.out.println("[Khasab reader] wide raw rows (incl. subcontractor cols 26-31): " + raw.size()
            + " — with manpower=" + mpRows
            + ", equipment=" + eqRows
            + ", material=" + matRows
            + ", subcontractor=" + subRows);

    record GroupKey(LocalDate date, String activityCode, String supervisor) {}

    Map<GroupKey, List<WideRawRow>> grouped = raw.stream()
            .filter(r -> r.date() != null && r.activityCode() != null && r.supervisor() != null)
            .collect(Collectors.groupingBy(
                    r -> new GroupKey(r.date(), r.activityCode(), r.supervisor()),
                    LinkedHashMap::new,
                    Collectors.toList()
            ));

    List<GroupedDpr> dprs = grouped.entrySet().stream()
            .map(e -> {
              GroupKey k = e.getKey();
              List<WideRawRow> rs = e.getValue();
              WideRawRow first = rs.get(0);

              // Combined chainage range: min/max across both From and To columns,
              // pooled together. Example: rows (6+400, 6+430) and (7+350, null)
              // collapse to From=6+400, To=7+350.
              Long chainageFrom = null;
              Long chainageTo = null;
              for (WideRawRow r : rs) {
                if (r.chainageFrom() != null) {
                  chainageFrom = (chainageFrom == null) ? r.chainageFrom() : Math.min(chainageFrom, r.chainageFrom());
                  chainageTo = (chainageTo == null) ? r.chainageFrom() : Math.max(chainageTo, r.chainageFrom());
                }
                if (r.chainageTo() != null) {
                  chainageFrom = (chainageFrom == null) ? r.chainageTo() : Math.min(chainageFrom, r.chainageTo());
                  chainageTo = (chainageTo == null) ? r.chainageTo() : Math.max(chainageTo, r.chainageTo());
                }
              }

              // Summed executed quantity across rows in the group.
              BigDecimal qty = rs.stream()
                      .map(WideRawRow::executedQty)
                      .filter(java.util.Objects::nonNull)
                      .reduce(BigDecimal.ZERO, BigDecimal::add);
              if (qty.signum() == 0
                      && rs.stream().noneMatch(r -> r.executedQty() != null)) {
                qty = null;
              }

              return new GroupedDpr(
                      k.date(),
                      first.site(),
                      first.location(),
                      chainageFrom,
                      chainageTo,
                      k.activityCode(),
                      first.unit(),
                      qty,
                      k.supervisor(),
                      rs.stream()
                              .filter(r -> r.manpowerCategory() != null)
                              .map(r -> new Manpower(r.manpowerCategory(), r.manpowerNos(), r.manpowerHours(), r.manpowerRate(), r.manpowerCost()))
                              .toList(),
                      rs.stream()
                              .filter(r -> r.equipmentDetail() != null)
                              .map(r -> new Equipment(r.equipmentDetail(), r.equipmentNos(), r.equipmentHours(), r.equipmentRate(), r.equipmentCost()))
                              .toList(),
                      rs.stream()
                              .filter(r -> r.materialDescription() != null)
                              .map(r -> new Material(r.materialDescription(), r.materialUnit(), r.materialQuantity(), r.materialRate(), r.materialCost()))
                              .toList(),
                      rs.stream()
                              .filter(r -> r.subcontractorName() != null)
                              .map(r -> new Subcontractor(r.subcontractorName(), r.subcontractorWorkDescription(), r.subcontractorUnit(), r.subcontractorQuantity(), r.subcontractorRate(), r.subcontractorCost()))
                              .toList()
              );
            })
            .toList();

    System.out.println("[Khasab reader] grouped DPRs: " + dprs.size());
    dprs.stream().limit(3).forEach(this::printDpr);

    System.out.println("[Khasab reader] DPRs from screenshots (24-Jan-25, Mohd Ismaila):");
    dprs.stream()
            .filter(d -> LocalDate.of(2025, 1, 24).equals(d.date())
                    && "Mohd Ismaila".equals(d.supervisorName())
                    && ("2.3.6(i)a".equals(d.activityCode()) || "2.3.6(i)b".equals(d.activityCode())))
            .forEach(this::printDpr);

    long withSub = dprs.stream().filter(d -> !d.subcontractor().isEmpty()).count();
    System.out.println("  DPRs with at least one subcontractor row: " + withSub);

    assertThat(dprs).as("grouped DPRs").isNotEmpty();
    assertThat(dprs).anyMatch(d -> !d.manpower().isEmpty());
    assertThat(dprs).anyMatch(d -> !d.equipment().isEmpty());

    // Verify the worked example from the screenshots:
    //   24-Jan-25, activity 2.3.6(i)a, supervisor Mohd Ismaila →
    //     rows (6+400, 6+430, qty=705) + (7+350, null, qty=200) + 3 empty rows
    //     → From=6+400, To=7+350, qty=905
    GroupedDpr sample = dprs.stream()
            .filter(d -> LocalDate.of(2025, 1, 24).equals(d.date())
                    && "Mohd Ismaila".equals(d.supervisorName())
                    && "2.3.6(i)a".equals(d.activityCode()))
            .findFirst()
            .orElseThrow();
    assertThat(sample.chainageFromM()).as("combined From").isEqualTo(6400L);
    assertThat(sample.chainageToM()).as("combined To").isEqualTo(7350L);
    assertThat(sample.executedQty()).as("summed Qty")
            .isEqualByComparingTo(new BigDecimal("905"));
  }

  private void printDpr(GroupedDpr d) {
    System.out.println("  DPR: date=" + d.date()
            + " activity=" + d.activityCode()
            + " supervisor=" + d.supervisorName()
            + " chainage=" + formatChainage(d.chainageFromM()) + " -> " + formatChainage(d.chainageToM())
            + " qty(sum)=" + d.executedQty());
    System.out.println("    manpower (" + d.manpower().size() + "):");
    d.manpower().forEach(m -> System.out.println("      " + m));
    System.out.println("    equipment (" + d.equipment().size() + "):");
    d.equipment().forEach(eq -> System.out.println("      " + eq));
    System.out.println("    material (" + d.material().size() + "):");
    d.material().forEach(m -> System.out.println("      " + m));
    System.out.println("    subcontractor (" + d.subcontractor().size() + "):");
    d.subcontractor().forEach(s -> System.out.println("      " + s));
  }

  /** Formats a chainage in metres as {@code km+mmm.00}, e.g. 6400 → {@code 6+400.00}. */
  private static String formatChainage(Long m) {
    if (m == null) return "—";
    long km = m / 1000;
    long rem = m % 1000;
    return String.format(Locale.ENGLISH, "%d+%03d.00", km, rem);
  }

  // ───────────────────────── Aggregate record types ─────────────────────────
  // Defined test-locally; production DailyDataRawRow stays flat to keep
  // KhasabDailyDataSeeder's expectations intact.

  /** Per-row manpower entry (Excel cols 11-15: Category, Nr., Hours, Rate/Hr, Cost). */
  record Manpower(String category, Integer nos, BigDecimal hours, BigDecimal rate, BigDecimal cost) {}

  /** Per-row equipment ("PmV") entry (Excel cols 16-20: Detail, Nr., Hours, Rate/Hr, Cost). */
  record Equipment(String detail, Integer nos, BigDecimal hours, BigDecimal rate, BigDecimal cost) {}

  /** Per-row material entry (Excel cols 21-25: Description, Unit, Quantity, Rate, Cost). */
  record Material(String description, String unit, BigDecimal quantity, BigDecimal rate, BigDecimal cost) {}

  /** Per-row subcontractor entry (Excel cols 26-31: Name, Work Description, Unit, Quantity, Rate, Cost). */
  record Subcontractor(String name, String workDescription, String unit, BigDecimal quantity, BigDecimal rate, BigDecimal cost) {}

  /** Grouped DPR — one entry per (date, activityCode, supervisor) carrying lists of resources. */
  record GroupedDpr(
          LocalDate date,
          String site,
          String location,
          Long chainageFromM,
          Long chainageToM,
          String activityCode,
          String unit,
          BigDecimal executedQty,
          String supervisorName,
          List<Manpower> manpower,
          List<Equipment> equipment,
          List<Material> material,
          List<Subcontractor> subcontractor
  ) {}

  // ───────────────────────── Raw row + workbook helper ─────────────────────────
  // The production reader does not expose subcontractor cells (cols 26-31), so the
  // test reopens the workbook itself.

  private record WideRawRow(
          LocalDate date, String site, String location,
          Long chainageFrom, Long chainageTo,
          String activityCode, String unit, BigDecimal executedQty, String supervisor,
          String manpowerCategory, Integer manpowerNos, BigDecimal manpowerHours, BigDecimal manpowerRate, BigDecimal manpowerCost,
          String equipmentDetail, Integer equipmentNos, BigDecimal equipmentHours, BigDecimal equipmentRate, BigDecimal equipmentCost,
          String materialDescription, String materialUnit, BigDecimal materialQuantity, BigDecimal materialRate, BigDecimal materialCost,
          String subcontractorName, String subcontractorWorkDescription, String subcontractorUnit,
          BigDecimal subcontractorQuantity, BigDecimal subcontractorRate, BigDecimal subcontractorCost
  ) {}

  private static final DataFormatter FORMATTER = new DataFormatter(Locale.ENGLISH);

  private List<WideRawRow> readWideRawRows() {
    ClassPathResource res = new ClassPathResource(KhasabDailyDataWorkbookReader.DAILY_PATH);
    try (InputStream is = res.getInputStream();
         Workbook wb = new XSSFWorkbook(is)) {
      List<WideRawRow> out = new ArrayList<>();
      for (String sheetName : List.of("Jan-2026", "Feb-2026", "March-2026")) {
        Sheet s = wb.getSheet(sheetName);
        if (s == null) continue;
        for (int i = 4; i <= s.getLastRowNum(); i++) {
          Row r = s.getRow(i);
          if (r == null) continue;
          LocalDate date = cellDate(r.getCell(1));
          if (date == null) continue;
          String supervisor = cellStr(r.getCell(10));
          String activityCode = cellStr(r.getCell(7));
          String mp = cellStr(r.getCell(11));
          String eq = cellStr(r.getCell(16));
          String mat = cellStr(r.getCell(21));
          String sub = cellStr(r.getCell(26));
          if (supervisor == null && activityCode == null && mp == null && eq == null && mat == null && sub == null) {
            continue;
          }
          out.add(new WideRawRow(
                  date,
                  cellStr(r.getCell(2)),
                  cellStr(r.getCell(3)),
                  cellLong(r.getCell(4)),
                  cellLong(r.getCell(5)),
                  activityCode,
                  cellStr(r.getCell(8)),
                  cellDec(r.getCell(9)),
                  supervisor,
                  mp, cellInt(r.getCell(12)), cellDec(r.getCell(13)), cellDec(r.getCell(14)), cellDec(r.getCell(15)),
                  eq, cellInt(r.getCell(17)), cellDec(r.getCell(18)), cellDec(r.getCell(19)), cellDec(r.getCell(20)),
                  mat, cellStr(r.getCell(22)), cellDec(r.getCell(23)), cellDec(r.getCell(24)), cellDec(r.getCell(25)),
                  sub, cellStr(r.getCell(27)), cellStr(r.getCell(28)),
                  cellDec(r.getCell(29)), cellDec(r.getCell(30)), cellDec(r.getCell(31))
          ));
        }
      }
      return out;
    } catch (Exception e) {
      throw new RuntimeException("Failed reading workbook: " + e.getMessage(), e);
    }
  }

  private static String cellStr(Cell c) {
    if (c == null) return null;
    CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
    if (type == CellType.BLANK || type == CellType.ERROR) return null;
    String v = FORMATTER.formatCellValue(c).trim();
    if (v.isEmpty() || "-".equals(v) || "—".equals(v) || "#REF!".equals(v)) return null;
    return v;
  }

  private static BigDecimal cellDec(Cell c) {
    if (c == null) return null;
    CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
    if (type == CellType.NUMERIC) {
      return BigDecimal.valueOf(c.getNumericCellValue());
    }
    String s = cellStr(c);
    if (s == null) return null;
    try {
      return new BigDecimal(s.replace(",", "").trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static Integer cellInt(Cell c) {
    BigDecimal v = cellDec(c);
    return v == null ? null : v.intValue();
  }

  private static Long cellLong(Cell c) {
    BigDecimal v = cellDec(c);
    return v == null ? null : v.longValue();
  }

  private static LocalDate cellDate(Cell c) {
    if (c == null) return null;
    try {
      CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
      if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
        Date d = c.getDateCellValue();
        return d == null ? null : d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  @Test
  void concrete_pours_are_parsed_for_khasab_and_lima() {
    List<ConcretePourRow> rows = reader.readConcretePours();
    System.out.println("[Khasab reader] concrete pours: " + rows.size());
    rows.stream().limit(5).forEach(r -> System.out.println("  POUR: " + r));

    assertThat(rows).isNotEmpty();
    assertThat(rows).anyMatch(r -> "Khasab".equals(r.site()));
    assertThat(rows).anyMatch(r -> "Lima".equals(r.site()));
  }

  @Test
  void productivity_norms_are_parsed() {
    List<ProductivityNormRow> rows = reader.readProductivityNorms();
    System.out.println("[Khasab reader] productivity norms: " + rows.size());
    rows.stream().limit(5).forEach(r -> System.out.println("  NORM: " + r));

    // Reader is best-effort on the norms sheet (merged-cell grouped layout);
    // require non-null but allow empty if probing fails.
    assertThat(rows).isNotNull();
  }

  @Test
  void pick_candidate_dprs_for_e2e() {
    // Goal: pick 2 grouped DPRs per month (Jan/Feb/March 2025) covering exactly
    // 3 activity codes total, all under one supervisor (Mohd Ismaila preferred).
    // Used as input for the DPR → BOQ → DBS E2E runbook.
    final String preferredSupervisor = "Mohd Ismaila";

    List<WideRawRow> raw = readWideRawRows();

    record GroupKey(LocalDate date, String activityCode, String supervisor) {}
    Map<GroupKey, List<WideRawRow>> grouped = raw.stream()
            .filter(r -> r.date() != null && r.activityCode() != null && r.supervisor() != null)
            .filter(r -> preferredSupervisor.equals(r.supervisor()))
            .collect(Collectors.groupingBy(
                    r -> new GroupKey(r.date(), r.activityCode(), r.supervisor()),
                    LinkedHashMap::new,
                    Collectors.toList()));

    List<GroupedDpr> dprs = grouped.entrySet().stream().map(e -> {
      GroupKey k = e.getKey();
      List<WideRawRow> rs = e.getValue();
      WideRawRow first = rs.get(0);
      Long cFrom = null, cTo = null;
      for (WideRawRow r : rs) {
        if (r.chainageFrom() != null) {
          cFrom = (cFrom == null) ? r.chainageFrom() : Math.min(cFrom, r.chainageFrom());
          cTo = (cTo == null) ? r.chainageFrom() : Math.max(cTo, r.chainageFrom());
        }
        if (r.chainageTo() != null) {
          cFrom = (cFrom == null) ? r.chainageTo() : Math.min(cFrom, r.chainageTo());
          cTo = (cTo == null) ? r.chainageTo() : Math.max(cTo, r.chainageTo());
        }
      }
      BigDecimal qty = rs.stream()
              .map(WideRawRow::executedQty)
              .filter(java.util.Objects::nonNull)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      if (qty.signum() == 0 && rs.stream().noneMatch(r -> r.executedQty() != null)) qty = null;
      return new GroupedDpr(
              k.date(), first.site(), first.location(), cFrom, cTo,
              k.activityCode(), first.unit(), qty, k.supervisor(),
              rs.stream().filter(r -> r.manpowerCategory() != null)
                      .map(r -> new Manpower(r.manpowerCategory(), r.manpowerNos(), r.manpowerHours(), r.manpowerRate(), r.manpowerCost()))
                      .toList(),
              rs.stream().filter(r -> r.equipmentDetail() != null)
                      .map(r -> new Equipment(r.equipmentDetail(), r.equipmentNos(), r.equipmentHours(), r.equipmentRate(), r.equipmentCost()))
                      .toList(),
              rs.stream().filter(r -> r.materialDescription() != null)
                      .map(r -> new Material(r.materialDescription(), r.materialUnit(), r.materialQuantity(), r.materialRate(), r.materialCost()))
                      .toList(),
              rs.stream().filter(r -> r.subcontractorName() != null)
                      .map(r -> new Subcontractor(r.subcontractorName(), r.subcontractorWorkDescription(), r.subcontractorUnit(), r.subcontractorQuantity(), r.subcontractorRate(), r.subcontractorCost()))
                      .toList());
    }).toList();

    System.out.println("[picker] DPRs for " + preferredSupervisor + " total: " + dprs.size());

    // Activity-code frequency across this supervisor's DPRs.
    Map<String, Long> codeFreq = dprs.stream()
            .collect(Collectors.groupingBy(GroupedDpr::activityCode, Collectors.counting()));
    List<String> topCodes = codeFreq.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .limit(8)
            .toList();
    System.out.println("[picker] top activity codes for this supervisor (by DPR count):");
    topCodes.forEach(c -> System.out.println("  " + c + " → " + codeFreq.get(c) + " DPRs"));

    // Need 3 codes that appear in every month. Find them.
    Map<Month, List<GroupedDpr>> byMonth = dprs.stream()
            .collect(Collectors.groupingBy(d -> d.date().getMonth()));
    Map<Month, java.util.Set<String>> monthlyCodes = new java.util.HashMap<>();
    for (var e : byMonth.entrySet()) {
      monthlyCodes.put(e.getKey(),
              e.getValue().stream().map(GroupedDpr::activityCode).collect(Collectors.toSet()));
    }
    System.out.println("[picker] activity codes present in EACH of Jan/Feb/Mar 2025:");
    java.util.Set<String> codesInAllMonths = new java.util.TreeSet<>();
    java.util.Set<String> seed = monthlyCodes.getOrDefault(Month.JANUARY, java.util.Set.of());
    for (String code : seed) {
      if (monthlyCodes.getOrDefault(Month.FEBRUARY, java.util.Set.of()).contains(code)
              && monthlyCodes.getOrDefault(Month.MARCH, java.util.Set.of()).contains(code)) {
        codesInAllMonths.add(code);
      }
    }
    codesInAllMonths.forEach(c -> System.out.println("  " + c));

    // Pick 3 codes that appear in all months AND have the highest cross-month DPR counts.
    List<String> chosenCodes = codesInAllMonths.stream()
            .sorted((a, b) -> Long.compare(codeFreq.get(b), codeFreq.get(a)))
            .limit(3)
            .toList();
    System.out.println("[picker] chosen 3 activity codes: " + chosenCodes);

    // For each month, pick 2 DPRs from the chosen codes, prefer ones with non-null qty + most resources.
    System.out.println();
    System.out.println("===== CANDIDATE 6 DPRs =====");
    for (Month m : List.of(Month.JANUARY, Month.FEBRUARY, Month.MARCH)) {
      List<GroupedDpr> picks = dprs.stream()
              .filter(d -> d.date().getMonth() == m)
              .filter(d -> chosenCodes.contains(d.activityCode()))
              .filter(d -> d.executedQty() != null && d.executedQty().signum() > 0)
              .sorted((a, b) -> {
                int byResources = Integer.compare(
                        b.manpower().size() + b.equipment().size() + b.material().size(),
                        a.manpower().size() + a.equipment().size() + a.material().size());
                if (byResources != 0) return byResources;
                return b.executedQty().compareTo(a.executedQty());
              })
              // Pick from 2 different codes if possible
              .collect(Collectors.collectingAndThen(Collectors.toList(), list -> {
                List<GroupedDpr> out = new ArrayList<>();
                java.util.Set<String> usedCodes = new java.util.HashSet<>();
                for (GroupedDpr d : list) {
                  if (out.size() >= 2) break;
                  if (usedCodes.contains(d.activityCode())) continue;
                  out.add(d);
                  usedCodes.add(d.activityCode());
                }
                // If we didn't get 2 distinct codes (rare), fill with any
                for (GroupedDpr d : list) {
                  if (out.size() >= 2) break;
                  if (!out.contains(d)) out.add(d);
                }
                return out;
              }));
      System.out.println("--- " + m + " 2025 ---");
      picks.forEach(this::printDpr);
    }
    System.out.println("===== END CANDIDATE 6 DPRs =====");

    assertThat(chosenCodes).hasSize(3);
  }

  // ───────────────────────── Debug utility: insert DPRs into DB ─────────────────────────
  //
  // Activated only when the test is run with -Ddpr.project.id=<uuid>. POSTs N DPRs per month
  // (default 2) into the given project via the running backend's REST API. Each DPR is built
  // from the combined-rows aggregation (date + activityCode + supervisor → one DPR with
  // List<Manpower>/<Equipment>/<Material>/<Subcontractor>), then linked to a SUPERVISOR user
  // seeded by KhasabSupervisorUserSeeder (the 11 supervisor names in the Khasab workbook).
  //
  // Usage:
  //   1. Start backend at :8080 (or override with -Ddpr.backend.url=...).
  //   2. Ensure the 11 Khasab supervisors exist (KhasabSupervisorUserSeeder runs under
  //      Spring profile "seed"). On a fresh DB:
  //        (cd backend && mvn -pl bipros-api -am spring-boot:run -Dspring-boot.run.profiles=seed)
  //   3. Get a projectId from /v1/projects, then:
  //        mvn -pl bipros-api test \
  //            -Dtest=KhasabDailyDataWorkbookReaderTest#insert_dprs_for_project \
  //            -Ddpr.project.id=<uuid> \
  //            -Dsurefire.failIfNoSpecifiedTests=false \
  //            -DforkCount=1 -DreuseForks=false -Dsurefire.useFile=false
  //
  // Optional overrides:
  //   -Ddpr.backend.url=http://localhost:8080
  //   -Ddpr.admin.username=admin
  //   -Ddpr.admin.password=admin123
  //   -Ddpr.per.month=2

  /** Canonical Khasab supervisor names (same list as KhasabSupervisorUserSeeder). */
  private static final List<String> SEEDED_SUPERVISORS = List.of(
          "A.K. Mishra",
          "Illayaraja",
          "K. Barman",
          "Manzar",
          "Md Saiffuddin",
          "Mohd Ismaila",
          "Parvaiz",
          "Sanjar Alam",
          "Sohail",
          "V.P. Gupta",
          "Vijaykumar"
  );

  @Test
  @EnabledIfSystemProperty(named = "dpr.project.id", matches = "[0-9a-fA-F-]{36}")
  void insert_dprs_for_project() throws Exception {
    String projectId = System.getProperty("dpr.project.id");
    String baseUrl = System.getProperty("dpr.backend.url", "http://localhost:8080");
    String adminUser = System.getProperty("dpr.admin.username", "admin");
    String adminPass = System.getProperty("dpr.admin.password", "admin123");
    int perMonth = Integer.parseInt(System.getProperty("dpr.per.month", "2"));

    System.out.println("[insert] projectId=" + projectId);
    System.out.println("[insert] backend=" + baseUrl + "  perMonth=" + perMonth);

    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    ObjectMapper json = new ObjectMapper();

    // 1. Login
    String token = login(http, json, baseUrl, adminUser, adminPass);
    System.out.println("[insert] login OK (token len=" + token.length() + ")");

    // 2. Look up the 11 seeded supervisors by display name (uses deterministic username
    //    convention from KhasabSupervisorUserSeeder.toUsername: lowercase + dot-separated).
    Map<String, String> supervisorIds = new TreeMap<>();
    for (String displayName : SEEDED_SUPERVISORS) {
      String username = toSupervisorUsername(displayName);
      String userId = lookupUserId(http, json, baseUrl, token, username);
      if (userId != null) supervisorIds.put(displayName, userId);
      else System.out.println("[insert] WARN — supervisor user not found: " + displayName + " (username=" + username + ")");
    }
    System.out.println("[insert] supervisors resolved: " + supervisorIds.size() + "/" + SEEDED_SUPERVISORS.size());
    supervisorIds.forEach((name, id) -> System.out.println("    " + name + " → " + id));
    assertThat(supervisorIds).as("seeded supervisors").isNotEmpty();

    // 3. Look up project activities (code → activityId).
    Map<String, String> activityByCode = lookupProjectActivities(http, json, baseUrl, token, projectId);
    System.out.println("[insert] project activities: " + activityByCode.size());
    activityByCode.forEach((c, id) -> System.out.println("    " + c + " → " + id));
    assertThat(activityByCode).as("project must have at least one activity").isNotEmpty();
    String fallbackActivityId = activityByCode.values().iterator().next();

    // 4. Build all combined-rows DPRs, keep only those whose supervisor matches a seeded user
    //    AND that have positive executed quantity.
    List<GroupedDpr> allDprs = buildAllGroupedDprs();
    Set<String> validSups = supervisorIds.keySet();
    List<GroupedDpr> eligible = allDprs.stream()
            .filter(d -> validSups.contains(d.supervisorName()))
            .filter(d -> d.executedQty() != null && d.executedQty().signum() > 0)
            .toList();
    System.out.println("[insert] eligible DPRs (seeded-supervisor & qty>0): " + eligible.size()
            + " / " + allDprs.size() + " total");

    // 5. Pick N per month, spreading across supervisors and activity codes.
    List<GroupedDpr> picks = pickPerMonth(eligible, perMonth);
    System.out.println("[insert] picked " + picks.size() + " DPRs:");
    picks.forEach(d -> System.out.println("    " + d.date()
            + " | act=" + d.activityCode()
            + " | sup=" + d.supervisorName()
            + " | qty=" + d.executedQty()
            + " | mp=" + d.manpower().size() + " eq=" + d.equipment().size()));

    // 6. POST each DPR.
    int success = 0;
    List<String> errors = new ArrayList<>();
    for (GroupedDpr d : picks) {
      String activityId = activityByCode.getOrDefault(d.activityCode(), fallbackActivityId);
      boolean exactMatch = activityByCode.containsKey(d.activityCode());
      String supervisorId = supervisorIds.get(d.supervisorName());
      ObjectNode body = buildDprPayload(json, d, activityId, supervisorId);
      try {
        String dprId = postDpr(http, baseUrl, token, projectId, body.toString());
        System.out.println("[insert] ✓ " + d.date()
                + " / " + d.activityCode() + (exactMatch ? "" : " (→ fallback activity)")
                + " / " + d.supervisorName()
                + " → DPR " + dprId);
        success++;
      } catch (Exception ex) {
        String tag = d.date() + " / " + d.activityCode() + " / " + d.supervisorName();
        System.out.println("[insert] ✗ " + tag + " : " + ex.getMessage());
        errors.add(tag + " : " + ex.getMessage());
      }
    }

    System.out.println("[insert] complete: success=" + success + "  failed=" + errors.size());
    errors.forEach(e -> System.out.println("    ERR: " + e));
    assertThat(errors).as("DPR insertion errors").isEmpty();
  }

  // ───────────────────────── Helpers for the insert test ─────────────────────────

  /** Mirrors KhasabSupervisorUserSeeder.toUsername — keep in sync. */
  private static String toSupervisorUsername(String fullName) {
    return fullName.toLowerCase().replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.+|\\.+$", "");
  }

  /** All combined-rows DPRs (across all supervisors / all months). */
  private List<GroupedDpr> buildAllGroupedDprs() {
    List<WideRawRow> raw = readWideRawRows();
    record GroupKey(LocalDate date, String activityCode, String supervisor) {}
    Map<GroupKey, List<WideRawRow>> grouped = raw.stream()
            .filter(r -> r.date() != null && r.activityCode() != null && r.supervisor() != null)
            .collect(Collectors.groupingBy(
                    r -> new GroupKey(r.date(), r.activityCode(), r.supervisor()),
                    LinkedHashMap::new, Collectors.toList()));
    return grouped.entrySet().stream().map(e -> {
      List<WideRawRow> rs = e.getValue();
      WideRawRow first = rs.get(0);
      Long cFrom = null, cTo = null;
      for (WideRawRow r : rs) {
        if (r.chainageFrom() != null) {
          cFrom = (cFrom == null) ? r.chainageFrom() : Math.min(cFrom, r.chainageFrom());
          cTo = (cTo == null) ? r.chainageFrom() : Math.max(cTo, r.chainageFrom());
        }
        if (r.chainageTo() != null) {
          cFrom = (cFrom == null) ? r.chainageTo() : Math.min(cFrom, r.chainageTo());
          cTo = (cTo == null) ? r.chainageTo() : Math.max(cTo, r.chainageTo());
        }
      }
      BigDecimal qty = rs.stream().map(WideRawRow::executedQty)
              .filter(java.util.Objects::nonNull)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      if (qty.signum() == 0 && rs.stream().noneMatch(r -> r.executedQty() != null)) qty = null;
      return new GroupedDpr(
              e.getKey().date(), first.site(), first.location(), cFrom, cTo,
              e.getKey().activityCode(), first.unit(), qty, e.getKey().supervisor(),
              rs.stream().filter(r -> r.manpowerCategory() != null)
                      .map(r -> new Manpower(r.manpowerCategory(), r.manpowerNos(), r.manpowerHours(), r.manpowerRate(), r.manpowerCost()))
                      .toList(),
              rs.stream().filter(r -> r.equipmentDetail() != null)
                      .map(r -> new Equipment(r.equipmentDetail(), r.equipmentNos(), r.equipmentHours(), r.equipmentRate(), r.equipmentCost()))
                      .toList(),
              rs.stream().filter(r -> r.materialDescription() != null)
                      .map(r -> new Material(r.materialDescription(), r.materialUnit(), r.materialQuantity(), r.materialRate(), r.materialCost()))
                      .toList(),
              rs.stream().filter(r -> r.subcontractorName() != null)
                      .map(r -> new Subcontractor(r.subcontractorName(), r.subcontractorWorkDescription(), r.subcontractorUnit(), r.subcontractorQuantity(), r.subcontractorRate(), r.subcontractorCost()))
                      .toList());
    }).toList();
  }

  /**
   * Pick N DPRs per month (Jan/Feb/Mar 2025), preferring distinct supervisors and distinct
   * activity codes within each month; ties broken by richest resource composition.
   */
  private List<GroupedDpr> pickPerMonth(List<GroupedDpr> eligible, int perMonth) {
    List<GroupedDpr> all = new ArrayList<>();
    for (Month m : List.of(Month.JANUARY, Month.FEBRUARY, Month.MARCH)) {
      List<GroupedDpr> monthly = eligible.stream()
              .filter(d -> d.date().getMonth() == m)
              .sorted((a, b) -> Integer.compare(
                      b.manpower().size() + b.equipment().size(),
                      a.manpower().size() + a.equipment().size()))
              .toList();
      List<GroupedDpr> picks = new ArrayList<>();
      Set<String> usedSups = new HashSet<>();
      Set<String> usedCodes = new HashSet<>();
      for (GroupedDpr d : monthly) {
        if (picks.size() >= perMonth) break;
        if (!usedSups.contains(d.supervisorName()) && !usedCodes.contains(d.activityCode())) {
          picks.add(d);
          usedSups.add(d.supervisorName());
          usedCodes.add(d.activityCode());
        }
      }
      // Fill remaining slots even if it means repeating a supervisor or code.
      for (GroupedDpr d : monthly) {
        if (picks.size() >= perMonth) break;
        if (!picks.contains(d)) picks.add(d);
      }
      all.addAll(picks);
    }
    return all;
  }

  private ObjectNode buildDprPayload(ObjectMapper json, GroupedDpr d, String activityId, String supervisorId) {
    ObjectNode body = json.createObjectNode();
    body.put("reportDate", d.date().toString());
    body.put("supervisorUserId", supervisorId);
    body.put("supervisorName", d.supervisorName());
    if (d.chainageFromM() != null) body.put("chainageFromM", d.chainageFromM());
    if (d.chainageToM() != null) body.put("chainageToM", d.chainageToM());
    body.put("activityId", activityId);
    body.put("activityName", d.activityCode());
    body.put("unit", d.unit() != null ? d.unit() : "cu.m.");
    body.put("qtyExecuted", d.executedQty());
    body.put("remarks", "Inserted by KhasabDailyDataWorkbookReaderTest#insert_dprs_for_project");

    ArrayNode mpArr = body.putArray("manpower");
    for (Manpower m : d.manpower()) {
      ObjectNode mp = mpArr.addObject();
      mp.put("trade", m.category());
      mp.put("category", "SKILLED");
      mp.put("shift", "DAY");
      mp.put("nos", m.nos() != null ? m.nos() : 1);
      if (m.hours() != null) mp.put("workingHours", m.hours());
      if (m.rate() != null) {
        mp.put("unitRate", m.rate());
        mp.put("unitRateBasis", "HOURLY");
      }
      if (m.cost() != null) mp.put("lineCost", m.cost());
    }

    ArrayNode eqArr = body.putArray("equipment");
    for (Equipment eq : d.equipment()) {
      ObjectNode e = eqArr.addObject();
      e.put("equipmentType", eq.detail());
      e.put("shift", "DAY");
      e.put("nos", eq.nos() != null ? eq.nos() : 1);
      if (eq.hours() != null) e.put("workingHours", eq.hours());
      if (eq.rate() != null) {
        e.put("unitRate", eq.rate());
        e.put("unitRateBasis", "HOURLY");
      }
      if (eq.cost() != null) e.put("lineCost", eq.cost());
    }

    ArrayNode matArr = body.putArray("materials");
    for (Material mat : d.material()) {
      ObjectNode mt = matArr.addObject();
      mt.put("materialDescription", mat.description());
      if (mat.unit() != null) mt.put("unit", mat.unit());
      if (mat.quantity() != null) mt.put("quantity", mat.quantity());
      if (mat.rate() != null) mt.put("unitRate", mat.rate());
      if (mat.cost() != null) mt.put("lineCost", mat.cost());
    }

    body.putArray("issues");
    return body;
  }

  private static String login(HttpClient http, ObjectMapper json, String baseUrl, String user, String pass) throws Exception {
    ObjectNode body = json.createObjectNode().put("username", user).put("password", pass);
    HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) throw new RuntimeException("login failed: " + resp.statusCode() + " " + resp.body());
    return json.readTree(resp.body()).path("data").path("accessToken").asText();
  }

  private static String lookupUserId(HttpClient http, ObjectMapper json, String baseUrl, String token, String username) throws Exception {
    HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/v1/users?size=200"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) return null;
    JsonNode content = json.readTree(resp.body()).path("data").path("content");
    for (JsonNode u : content) {
      if (username.equals(u.path("username").asText())) return u.path("id").asText();
    }
    return null;
  }

  private static Map<String, String> lookupProjectActivities(HttpClient http, ObjectMapper json, String baseUrl, String token, String projectId) throws Exception {
    HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/v1/projects/" + projectId + "/activities?size=200"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      throw new RuntimeException("activities fetch failed: " + resp.statusCode() + " " + resp.body());
    }
    JsonNode tree = json.readTree(resp.body()).path("data");
    JsonNode list = tree.has("content") ? tree.path("content") : tree;
    Map<String, String> out = new LinkedHashMap<>();
    for (JsonNode a : list) {
      String code = a.path("code").asText(null);
      String id = a.path("id").asText(null);
      if (code != null && id != null) out.put(code, id);
    }
    return out;
  }

  private static String postDpr(HttpClient http, String baseUrl, String token, String projectId, String body) throws Exception {
    HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/v1/projects/" + projectId + "/dpr"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2) {
      throw new RuntimeException("HTTP " + resp.statusCode() + " " + resp.body());
    }
    return new ObjectMapper().readTree(resp.body()).path("data").path("id").asText();
  }

  @Test
  void dump_header_layout_for_subcontractor_column_check() {
    // Diagnostic: print header rows 0-3 of Jan-2026 to verify which Excel columns
    // hold subcontractor (Name / Work Description / Unit / Quantity / Rate / Cost).
    ClassPathResource res = new ClassPathResource(KhasabDailyDataWorkbookReader.DAILY_PATH);
    try (InputStream is = res.getInputStream();
         Workbook wb = new XSSFWorkbook(is)) {
      Sheet s = wb.getSheet("Jan-2026");
      assertThat(s).as("Jan-2026 sheet").isNotNull();
      int maxCol = 35;
      System.out.println("[Khasab reader] Jan-2026 header layout (rows 0-3, cols 0-" + maxCol + "):");
      for (int r = 0; r <= 3; r++) {
        Row row = s.getRow(r);
        if (row == null) {
          System.out.println("  row " + r + ": (empty)");
          continue;
        }
        for (int c = 0; c <= maxCol; c++) {
          String v = cellStr(row.getCell(c));
          if (v != null) {
            System.out.println("  row " + r + " col " + c + ": " + v);
          }
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void dump_summary_for_diagnostics() {
    List<ActivityCodeRow> codes = reader.readActivityCodes();
    List<DailyDataRawRow> daily = reader.readAllDailyRows();
    List<ConcretePourRow> pours = reader.readConcretePours();
    List<ProductivityNormRow> norms = reader.readProductivityNorms();

    System.out.println("[Khasab reader] activities=" + codes.size()
        + " dailyRows=" + daily.size()
        + " concrete=" + pours.size()
        + " norms=" + norms.size());

    assertThat(codes).isNotNull();
    assertThat(daily).isNotNull();
    assertThat(pours).isNotNull();
    assertThat(norms).isNotNull();
  }
}
