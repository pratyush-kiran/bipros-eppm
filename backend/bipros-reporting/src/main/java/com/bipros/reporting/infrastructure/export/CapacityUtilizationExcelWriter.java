package com.bipros.reporting.infrastructure.export;

import com.bipros.reporting.application.dto.CapacityUtilizationReport;
import com.bipros.reporting.application.dto.CapacityUtilizationReport.Period;
import com.bipros.reporting.application.dto.CapacityUtilizationReport.Row;
import com.bipros.reporting.application.dto.DailyDeploymentMatrix;
import com.bipros.reporting.application.dto.DprMatrix;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the 5-sheet Capacity Utilisation workbook (Plant utilisation, Manpower utilisation,
 * SUMMARY, Daily Deployment, DPR) to an .xlsx byte array. Layout mirrors the
 * Capacity_Utilization.xlsx template used by the construction-industry site teams: merged group
 * headers, yellow Work-days highlight, banded resource-type subtotals, and the
 * {@code =Day! + 0.9 * Night!} formula on the Daily Deployment Total block.
 */
@Component
@Slf4j
public class CapacityUtilizationExcelWriter {

  private static final String[] CAPACITY_HEADERS = {
      "S.No.", "Description", "Budget Unit", "Prod'vity Norm", "Unit",
      "Work done Qty", "Budgeted Days", "Actual Days", "Act Days (Untracked)", "% Util",
      "Cum. Work done", "Budgeted Days", "Actual Days", "Act Days (Untracked)", "% Util"
  };

  public byte[] generate(
      CapacityUtilizationReport plant,
      CapacityUtilizationReport manpower,
      DailyDeploymentMatrix daily,
      DprMatrix dpr,
      YearMonth month,
      int workDays,
      String projectName) {
    try (XSSFWorkbook wb = new XSSFWorkbook()) {
      Styles s = new Styles(wb);
      writeCapacitySheet(wb, "Plant utilization", plant, month, workDays, projectName, s);
      writeCapacitySheet(wb, "Manpower utilization", manpower, month, workDays, projectName, s);
      writeSummarySheet(wb, plant, manpower, month, workDays, s);
      writeDailyDeploymentSheet(wb, daily, s);
      writeDprSheet(wb, dpr, s);
      return toByteArray(wb);
    } catch (IOException e) {
      log.error("Failed to generate Capacity Utilization Excel for month {}", month, e);
      throw new RuntimeException("Failed to generate Capacity Utilization workbook", e);
    }
  }

  // ── Sheets 1 & 2: Plant utilization / Manpower utilization ─────────────────────────────────
  private void writeCapacitySheet(
      XSSFWorkbook wb, String sheetName, CapacityUtilizationReport report,
      YearMonth month, int workDays, String projectName, Styles s) {
    XSSFSheet sh = wb.createSheet(sheetName);

    // Top metadata band: Work days · Date · Project Name.
    XSSFRow r1 = sh.createRow(0);
    setText(r1, 5, "Work days", s.bold);
    setNumber(r1, 6, workDays, s.workDaysHighlight);
    setText(r1, 10, "Date :", s.bold);
    setText(r1, 11, month.toString(), s.plain);

    XSSFRow r2 = sh.createRow(1);
    setText(r2, 0, "Resource Capacity Utilization Report — " + month, s.title);
    setText(r2, 10, "Project Name :", s.bold);
    setText(r2, 11, projectName == null ? "" : projectName, s.plain);

    // Header band rows 3-4 with merged group cells: Budget / Actual (For the day) / Cumulative (For the month)
    // Layout (0-based columns):
    //   0=S.No, 1=Description, 2=Unit, 3=Prod'vity Norm, 4=Unit,
    //   5-9   = Actual (For the day): Qty / Bud Days / Act Days / Act Days (Untracked) / % Util
    //   10-14 = Cumulative (For the month): Qty / Bud Days / Act Days / Act Days (Untracked) / % Util
    XSSFRow r3 = sh.createRow(2);
    XSSFRow r4 = sh.createRow(3);
    setText(r3, 0, "S.No.", s.headerCenter);
    setText(r3, 1, "Description", s.headerCenter);
    setText(r3, 2, "Budget", s.headerCenter);
    setText(r3, 4, "Unit", s.headerCenter);
    setText(r3, 5, "Actual (For the day)", s.headerCenter);
    setText(r3, 10, "Cumulative (For the month)", s.headerCenter);
    sh.addMergedRegion(new CellRangeAddress(2, 2, 2, 3));    // "Budget" spans Unit + Norm columns
    sh.addMergedRegion(new CellRangeAddress(2, 2, 5, 9));    // "Actual (For the day)" spans 5 metrics
    sh.addMergedRegion(new CellRangeAddress(2, 2, 10, 14));  // "Cumulative (For the month)" spans 5 metrics
    sh.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));    // S.No. vertical
    sh.addMergedRegion(new CellRangeAddress(2, 3, 1, 1));    // Description vertical
    sh.addMergedRegion(new CellRangeAddress(2, 3, 4, 4));    // Unit vertical

    setText(r4, 2, "Unit", s.headerCenter);
    setText(r4, 3, "Prod'vity Norm", s.headerCenter);
    setText(r4, 5, "Work done Qty", s.headerCenter);
    setText(r4, 6, "Budgeted Days", s.headerCenter);
    setText(r4, 7, "Actual Days", s.headerCenter);
    setText(r4, 8, "Act Days (Untracked)", s.headerCenter);
    setText(r4, 9, "% Util", s.headerCenter);
    setText(r4, 10, "Cum. Work done", s.headerCenter);
    setText(r4, 11, "Budgeted Days", s.headerCenter);
    setText(r4, 12, "Actual Days", s.headerCenter);
    setText(r4, 13, "Act Days (Untracked)", s.headerCenter);
    setText(r4, 14, "% Util", s.headerCenter);

    // Body — group Capacity rows by resource type / resource.
    Map<String, List<Row>> grouped = new LinkedHashMap<>();
    for (Row row : report.rows()) {
      grouped.computeIfAbsent(row.groupKey().displayLabel(), k -> new ArrayList<>()).add(row);
    }

    int rowNum = 5;
    int sNo = 1;
    for (Map.Entry<String, List<Row>> e : grouped.entrySet()) {
      // Group banner row with running S.No.
      XSSFRow groupRow = sh.createRow(rowNum++);
      setNumber(groupRow, 0, sNo++, s.groupBold);
      setText(groupRow, 1, e.getKey(), s.groupBold);
      // Aggregated day / month metrics
      Period dayAgg = aggregate(e.getValue(), true);
      Period monthAgg = aggregate(e.getValue(), false);
      setBigDecimal(groupRow, 5, dayAgg.qty(), s.numCell);
      setBigDecimal(groupRow, 6, dayAgg.budgetedDays(), s.numCell);
      setBigDecimal(groupRow, 7, dayAgg.actualDays(), s.numCell);
      setBigDecimal(groupRow, 8, dayAgg.actualDaysUntracked(), s.numCell);
      setBigDecimal(groupRow, 9, divideToFraction(dayAgg.budgetedDays(), dayAgg.actualDays()), s.pctCell);
      setBigDecimal(groupRow, 10, monthAgg.qty(), s.numCell);
      setBigDecimal(groupRow, 11, monthAgg.budgetedDays(), s.numCell);
      setBigDecimal(groupRow, 12, monthAgg.actualDays(), s.numCell);
      setBigDecimal(groupRow, 13, monthAgg.actualDaysUntracked(), s.numCell);
      setBigDecimal(groupRow, 14, divideToFraction(monthAgg.budgetedDays(), monthAgg.actualDays()), s.pctCell);
      for (int c = 2; c <= 4; c++) {
        groupRow.createCell(c).setCellStyle(s.groupBold);
      }

      for (Row row : e.getValue()) {
        XSSFRow body = sh.createRow(rowNum++);
        setText(body, 1, displayActivityLabel(row), s.plain);
        setText(body, 2, defaultUnit(row, "/day"), s.plain);
        setBigDecimal(body, 3, row.budgeted() != null ? row.budgeted().outputPerDay() : null, s.numCell);
        setText(body, 4, row.workActivity() != null ? row.workActivity().defaultUnit() : "", s.plain);
        setBigDecimal(body, 5, row.forTheDay().qty(), s.numCell);
        setBigDecimal(body, 6, row.forTheDay().budgetedDays(), s.numCell);
        setBigDecimal(body, 7, row.forTheDay().actualDays(), s.numCell);
        setBigDecimal(body, 8, row.forTheDay().actualDaysUntracked(), s.numCell);
        setBigDecimal(body, 9, toFraction(row.forTheDay().utilizationPct()), s.pctCell);
        setBigDecimal(body, 10, row.forTheMonth().qty(), s.numCell);
        setBigDecimal(body, 11, row.forTheMonth().budgetedDays(), s.numCell);
        setBigDecimal(body, 12, row.forTheMonth().actualDays(), s.numCell);
        setBigDecimal(body, 13, row.forTheMonth().actualDaysUntracked(), s.numCell);
        setBigDecimal(body, 14, toFraction(row.forTheMonth().utilizationPct()), s.pctCell);
      }
    }

    setColumnWidths(sh, new int[] {2200, 12000, 3200, 3200, 2400,
        3200, 3200, 3200, 3000, 2400, 3600, 3200, 3200, 3000, 2400});
  }

  // ── Sheet 3: SUMMARY ───────────────────────────────────────────────────────────────────────
  private void writeSummarySheet(
      XSSFWorkbook wb, CapacityUtilizationReport plant, CapacityUtilizationReport manpower,
      YearMonth month, int workDays, Styles s) {
    XSSFSheet sh = wb.createSheet("SUMMARY");

    XSSFRow r1 = sh.createRow(0);
    setText(r1, 1, "Work days", s.bold);
    setNumber(r1, 2, workDays, s.workDaysHighlight);
    XSSFRow r2 = sh.createRow(1);
    setText(r2, 1, "Date :", s.bold);
    setText(r2, 2, month.toString(), s.plain);
    setText(r2, 6, month.getMonth() + "-" + month.getYear(), s.titleUnderline);

    XSSFRow head1 = sh.createRow(3);
    setText(head1, 2, "For the Day", s.headerCenter);
    setText(head1, 5, "For the Month", s.headerCenter);
    sh.addMergedRegion(new CellRangeAddress(3, 3, 2, 4));
    sh.addMergedRegion(new CellRangeAddress(3, 3, 5, 7));

    XSSFRow head2 = sh.createRow(4);
    setText(head2, 1, "Equipment", s.headerCenter);
    setText(head2, 2, "Budg. Days", s.headerCenter);
    setText(head2, 3, "Actual Days", s.headerCenter);
    setText(head2, 4, "% of Util", s.headerCenter);
    setText(head2, 5, "Budg. Days", s.headerCenter);
    setText(head2, 6, "Actual Days", s.headerCenter);
    setText(head2, 7, "% of Util", s.headerCenter);

    int rowNum = 5;
    rowNum = writeSummaryBlock(sh, plant, rowNum, s);

    XSSFRow gap = sh.createRow(rowNum++);
    setText(gap, 1, "Manpower", s.groupBold);

    rowNum = writeSummaryBlock(sh, manpower, rowNum, s);
    setColumnWidths(sh, new int[] {800, 4000, 2800, 2800, 2400, 2800, 2800, 2400});
  }

  private int writeSummaryBlock(XSSFSheet sh, CapacityUtilizationReport report, int startRow, Styles s) {
    Map<String, List<Row>> grouped = new LinkedHashMap<>();
    for (Row r : report.rows()) {
      grouped.computeIfAbsent(r.groupKey().displayLabel(), k -> new ArrayList<>()).add(r);
    }
    int rowNum = startRow;
    for (Map.Entry<String, List<Row>> e : grouped.entrySet()) {
      Period dayAgg = aggregate(e.getValue(), true);
      Period monthAgg = aggregate(e.getValue(), false);
      XSSFRow row = sh.createRow(rowNum++);
      setText(row, 1, e.getKey(), s.plain);
      setBigDecimal(row, 2, dayAgg.budgetedDays(), s.numCell);
      setBigDecimal(row, 3, dayAgg.actualDays(), s.numCell);
      setBigDecimal(row, 4, divideToFraction(dayAgg.budgetedDays(), dayAgg.actualDays()), s.pctCell);
      setBigDecimal(row, 5, monthAgg.budgetedDays(), s.numCell);
      setBigDecimal(row, 6, monthAgg.actualDays(), s.numCell);
      setBigDecimal(row, 7, divideToFraction(monthAgg.budgetedDays(), monthAgg.actualDays()), s.pctCell);
    }
    return rowNum;
  }

  // ── Sheet 4: Daily Deployment ──────────────────────────────────────────────────────────────
  private void writeDailyDeploymentSheet(XSSFWorkbook wb, DailyDeploymentMatrix daily, Styles s) {
    XSSFSheet sh = wb.createSheet("Daily Deployment");
    int rowNum = 0;
    String monthLabel = daily.month().toString();
    int days = daily.daysInMonth();

    String[] sectionTitles = {
        "Total hours deployed everyday FTM " + monthLabel + " (Day Shift)",
        "Total hours deployed everyday FTM " + monthLabel + " (Night Shift)",
        "Total hours deployed everyday FTM " + monthLabel
    };

    for (int sectionIdx = 0; sectionIdx < daily.sections().size() && sectionIdx < sectionTitles.length; sectionIdx++) {
      DailyDeploymentMatrix.Section section = daily.sections().get(sectionIdx);

      XSSFRow titleRow = sh.createRow(rowNum++);
      setText(titleRow, 0, sectionTitles[sectionIdx], s.title);

      XSSFRow header = sh.createRow(rowNum++);
      setText(header, 0, "Sl.Nr.", s.headerCenter);
      setText(header, 1, "Vehicle/Equipment", s.headerCenter);
      setText(header, 2, "Plan hours", s.headerCenter);
      for (int d = 1; d <= days; d++) {
        setText(header, 2 + d, String.valueOf(d), s.headerCenter);
      }
      setText(header, 3 + days, "Total", s.headerCenter);

      int sl = 1;
      for (DailyDeploymentMatrix.Row r : section.rows()) {
        XSSFRow body = sh.createRow(rowNum++);
        setNumber(body, 0, sl++, s.numCell);
        setText(body, 1, r.resourceLabel(), s.plain);
        setBigDecimal(body, 2, r.planHours(), s.numCell);
        for (int d = 0; d < days; d++) {
          XSSFCell c = body.createCell(3 + d);
          BigDecimal v = r.hoursPerDay() != null ? r.hoursPerDay()[d] : null;
          if (v != null) c.setCellValue(v.doubleValue());
          c.setCellStyle(s.numCell);
        }
        setBigDecimal(body, 3 + days, r.total(), s.numCell);
      }
      // Spacer row between sections.
      rowNum += 1;
    }

    int[] widths = new int[3 + days + 1];
    widths[0] = 1500;
    widths[1] = 5500;
    widths[2] = 2800;
    for (int d = 0; d < days; d++) widths[3 + d] = 1100;
    widths[3 + days] = 1800;
    setColumnWidths(sh, widths);
  }

  // ── Sheet 5: DPR ───────────────────────────────────────────────────────────────────────────
  private void writeDprSheet(XSSFWorkbook wb, DprMatrix dpr, Styles s) {
    XSSFSheet sh = wb.createSheet("DPR");
    int rowNum = 0;
    int days = dpr.daysInMonth();

    XSSFRow header0 = sh.createRow(rowNum++);
    setText(header0, 0, "Progress for the Month of " + dpr.month(), s.title);

    XSSFRow projRow = sh.createRow(rowNum++);
    setText(projRow, 0, "PROJECT :", s.bold);
    setText(projRow, 1, dpr.projectName() == null ? "" : dpr.projectName(), s.plain);

    XSSFRow clientRow = sh.createRow(rowNum++);
    setText(clientRow, 0, "CLIENT :", s.bold);
    setText(clientRow, 1, dpr.client() == null ? "" : dpr.client(), s.plain);

    XSSFRow engRow = sh.createRow(rowNum++);
    setText(engRow, 0, "ENGINEER :", s.bold);
    setText(engRow, 1, dpr.engineer() == null ? "" : dpr.engineer(), s.plain);

    XSSFRow contRow = sh.createRow(rowNum++);
    setText(contRow, 0, "CONTRACTOR :", s.bold);
    setText(contRow, 1, dpr.contractor() == null ? "" : dpr.contractor(), s.plain);

    rowNum++; // spacer

    // Group header rows
    XSSFRow group = sh.createRow(rowNum++);
    setText(group, 4, "Projection", s.headerCenter);
    setText(group, 6, "Achieved", s.headerCenter);
    sh.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 4, 5));
    sh.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 6, 7));

    XSSFRow head = sh.createRow(rowNum++);
    setText(head, 0, "ITEM NO", s.headerCenter);
    setText(head, 1, "ITEM DESCRIPTION", s.headerCenter);
    setText(head, 2, "Unit", s.headerCenter);
    setText(head, 3, "Revised Unit Rate", s.headerCenter);
    setText(head, 4, "QTY", s.headerCenter);
    setText(head, 5, "Amount", s.headerCenter);
    setText(head, 6, "QTY", s.headerCenter);
    setText(head, 7, "Amount", s.headerCenter);
    for (int d = 1; d <= days; d++) {
      setText(head, 7 + d, "date " + d, s.headerCenter);
    }

    for (DprMatrix.Item it : dpr.items()) {
      XSSFRow body = sh.createRow(rowNum++);
      setText(body, 0, it.itemNo(), s.plain);
      setText(body, 1, it.description(), s.plain);
      setText(body, 2, it.unit(), s.plain);
      setBigDecimal(body, 3, it.revisedRate(), s.numCell);
      setBigDecimal(body, 4, it.projectionQty(), s.numCell);
      setBigDecimal(body, 5, it.projectionAmount(), s.numCell);
      setBigDecimal(body, 6, it.achievedQty(), s.numCell);
      setBigDecimal(body, 7, it.achievedAmount(), s.numCell);
      for (int d = 0; d < days; d++) {
        BigDecimal v = it.qtyPerDay() != null ? it.qtyPerDay()[d] : null;
        XSSFCell c = body.createCell(8 + d);
        if (v != null) {
          c.setCellValue(v.doubleValue());
        }
        c.setCellStyle(s.numCell);
      }
    }

    int[] widths = new int[8 + days];
    widths[0] = 2500;
    widths[1] = 16000;
    widths[2] = 1800;
    widths[3] = 3200;
    widths[4] = 3000;
    widths[5] = 3500;
    widths[6] = 3000;
    widths[7] = 3500;
    for (int d = 0; d < days; d++) widths[8 + d] = 1600;
    setColumnWidths(sh, widths);
  }

  // ── Helpers ────────────────────────────────────────────────────────────────────────────────
  private String displayActivityLabel(Row row) {
    if (row.workActivity() == null) return "";
    String name = row.workActivity().name();
    String code = row.workActivity().code();
    if (code == null || code.isBlank()) return name == null ? "" : name;
    if (name == null || name.isBlank()) return code;
    return name;
  }

  private String defaultUnit(Row row, String suffix) {
    if (row.workActivity() == null || row.workActivity().defaultUnit() == null) return "";
    return row.workActivity().defaultUnit() + suffix;
  }

  private static Period aggregate(List<Row> rows, boolean dayBucket) {
    BigDecimal qty = BigDecimal.ZERO;
    BigDecimal bud = BigDecimal.ZERO;
    BigDecimal act = BigDecimal.ZERO;
    BigDecimal untracked = BigDecimal.ZERO;
    boolean anyBud = false;
    boolean anyUntracked = false;
    for (Row row : rows) {
      Period p = dayBucket ? row.forTheDay() : row.forTheMonth();
      if (p.qty() != null) qty = qty.add(p.qty());
      if (p.budgetedDays() != null) {
        bud = bud.add(p.budgetedDays());
        anyBud = true;
      }
      if (p.actualDays() != null) act = act.add(p.actualDays());
      if (p.actualDaysUntracked() != null) {
        untracked = untracked.add(p.actualDaysUntracked());
        anyUntracked = true;
      }
    }
    return new Period(qty, anyBud ? bud : null, act, null, null,
        anyUntracked ? untracked : null);
  }

  /** util% as displayed comes back as 0..999 (percent). Convert to 0..1 for Excel's % format. */
  private static BigDecimal toFraction(BigDecimal pct) {
    if (pct == null) return null;
    return pct.movePointLeft(2);
  }

  private static BigDecimal divideToFraction(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null || denominator.signum() == 0) return null;
    return numerator.divide(denominator, 4, java.math.RoundingMode.HALF_UP);
  }

  private static void setText(XSSFRow row, int col, String value, CellStyle style) {
    XSSFCell c = row.createCell(col, CellType.STRING);
    c.setCellValue(value == null ? "" : value);
    if (style != null) c.setCellStyle(style);
  }

  private static void setNumber(XSSFRow row, int col, double value, CellStyle style) {
    XSSFCell c = row.createCell(col, CellType.NUMERIC);
    c.setCellValue(value);
    if (style != null) c.setCellStyle(style);
  }

  private static void setBigDecimal(XSSFRow row, int col, BigDecimal value, CellStyle style) {
    XSSFCell c = row.createCell(col, value == null ? CellType.BLANK : CellType.NUMERIC);
    if (value != null) c.setCellValue(value.doubleValue());
    if (style != null) c.setCellStyle(style);
  }

  private static void setColumnWidths(XSSFSheet sh, int[] widths) {
    for (int i = 0; i < widths.length; i++) sh.setColumnWidth(i, widths[i]);
  }

  private static byte[] toByteArray(XSSFWorkbook wb) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    wb.write(baos);
    return baos.toByteArray();
  }

  /** Per-workbook style cache so we don't blow Excel's 64k-style limit on large books. */
  private static final class Styles {
    final CellStyle plain;
    final CellStyle bold;
    final CellStyle title;
    final CellStyle titleUnderline;
    final CellStyle headerCenter;
    final CellStyle groupBold;
    final CellStyle numCell;
    final CellStyle pctCell;
    final CellStyle workDaysHighlight;

    Styles(XSSFWorkbook wb) {
      DataFormat df = wb.createDataFormat();

      Font fontPlain = wb.createFont();
      Font fontBold = wb.createFont();
      fontBold.setBold(true);
      Font fontTitle = wb.createFont();
      fontTitle.setBold(true);
      fontTitle.setFontHeightInPoints((short) 12);
      Font fontTitleU = wb.createFont();
      fontTitleU.setBold(true);
      fontTitleU.setUnderline(Font.U_SINGLE);

      plain = wb.createCellStyle();
      plain.setFont(fontPlain);
      borderAll(plain);

      bold = wb.createCellStyle();
      bold.setFont(fontBold);

      title = wb.createCellStyle();
      title.setFont(fontTitle);

      titleUnderline = wb.createCellStyle();
      titleUnderline.setFont(fontTitleU);

      headerCenter = wb.createCellStyle();
      headerCenter.setFont(fontBold);
      headerCenter.setAlignment(HorizontalAlignment.CENTER);
      headerCenter.setVerticalAlignment(VerticalAlignment.CENTER);
      headerCenter.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      headerCenter.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      borderAll(headerCenter);
      headerCenter.setWrapText(true);

      groupBold = wb.createCellStyle();
      groupBold.setFont(fontBold);
      groupBold.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
      groupBold.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      borderAll(groupBold);

      numCell = wb.createCellStyle();
      numCell.setFont(fontPlain);
      numCell.setDataFormat(df.getFormat("#,##0.00"));
      numCell.setAlignment(HorizontalAlignment.RIGHT);
      borderAll(numCell);

      pctCell = wb.createCellStyle();
      pctCell.setFont(fontPlain);
      pctCell.setDataFormat(df.getFormat("0%"));
      pctCell.setAlignment(HorizontalAlignment.RIGHT);
      borderAll(pctCell);

      workDaysHighlight = wb.createCellStyle();
      workDaysHighlight.setFont(fontBold);
      workDaysHighlight.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
      workDaysHighlight.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      workDaysHighlight.setAlignment(HorizontalAlignment.CENTER);
      borderAll(workDaysHighlight);
    }

    private static void borderAll(CellStyle s) {
      s.setBorderBottom(BorderStyle.THIN);
      s.setBorderTop(BorderStyle.THIN);
      s.setBorderLeft(BorderStyle.THIN);
      s.setBorderRight(BorderStyle.THIN);
    }
  }
}
