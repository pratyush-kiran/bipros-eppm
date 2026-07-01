package com.bipros.reporting.infrastructure.export;

import com.bipros.reporting.application.dto.DprCostingReport;
import com.bipros.reporting.application.dto.DprCostingReport.Block;
import com.bipros.reporting.application.dto.DprCostingReport.Manpower;
import com.bipros.reporting.application.dto.DprCostingReport.Material;
import com.bipros.reporting.application.dto.DprCostingReport.Pmv;
import com.bipros.reporting.application.dto.DprCostingReport.SubContract;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the "Daily Activity Costing" workbook — one sheet per calendar month — from a
 * {@link DprCostingReport}. Layout mirrors the site teams' {@code DPR monthwise.xlsx}: a project
 * title band, a two-row header with merged Manpower / PmV / Material / Subcontract groups, then
 * per-activity blocks whose resource line-items stack in parallel columns. Cost columns are
 * {@code Nr × Rate} (Material = stored line cost); the right-side progress columns derive from
 * chainage and the BOQ contract quantity. Unlike the sample, every value is a literal computed in
 * our DB — no external {@code VLOOKUP}s, so the file opens clean with no {@code #REF!}.
 */
@Component
@Slf4j
public class DprCostingExcelWriter {

  private static final DateTimeFormatter SHEET_FMT = DateTimeFormatter.ofPattern("MMM yyyy");

  // ── Column indices ───────────────────────────────────────────────────────────────────────────
  private static final int C_SNO = 0;
  private static final int C_DATE = 1;
  private static final int C_SITE = 2;
  private static final int C_LOCATION = 3;
  private static final int C_FROM = 4;
  private static final int C_TO = 5;
  private static final int C_SIDE = 6;
  private static final int C_ACTIVITY = 7;
  private static final int C_UNIT = 8;
  private static final int C_EXEC_QTY = 9;
  private static final int C_NAME = 10;          // supervisor name — standalone, after Executed Qty
  private static final int C_MP_CATEGORY = 11;
  private static final int C_MP_NR = 12;
  private static final int C_MP_RATE = 13;
  private static final int C_MP_COST = 14;
  private static final int C_PMV_DETAIL = 15;
  private static final int C_PMV_NR = 16;
  private static final int C_PMV_RATE = 17;
  private static final int C_PMV_COST = 18;
  private static final int C_MAT_DESC = 19;
  private static final int C_MAT_UNIT = 20;
  private static final int C_MAT_QTY = 21;
  private static final int C_MAT_RATE = 22;
  private static final int C_MAT_COST = 23;
  private static final int C_SC_NAME = 24;
  private static final int C_SC_WORK = 25;
  private static final int C_SC_UNIT = 26;
  private static final int C_SC_QTY = 27;
  private static final int C_SC_RATE = 28;
  private static final int C_SC_COST = 29;
  private static final int C_REMARKS = 30;
  private static final int C_LENGTH = 31;
  private static final int C_TOTAL_QTY = 32;
  private static final int C_PROG_QTY = 33;
  private static final int C_PROG_PCT = 34;
  private static final int C_PROG_LENGTH = 35;
  private static final int LAST_COL = C_PROG_LENGTH;

  private static final int GROUP_HEADER_ROW = 2;
  private static final int HEADER_ROW = 3;
  private static final int FIRST_DATA_ROW = 4;

  public byte[] generate(DprCostingReport report) {
    try (XSSFWorkbook wb = new XSSFWorkbook()) {
      Styles s = new Styles(wb);
      String projectName = report.projectName();

      if (report.blocks().isEmpty()) {
        writeSheet(wb, "DPR", projectName, List.of(), s);
      } else {
        Map<YearMonth, List<Block>> byMonth = new LinkedHashMap<>();
        for (Block b : report.blocks()) {
          byMonth.computeIfAbsent(YearMonth.from(b.date()), k -> new ArrayList<>()).add(b);
        }
        for (Map.Entry<YearMonth, List<Block>> e : byMonth.entrySet()) {
          writeSheet(wb, e.getKey().format(SHEET_FMT), projectName, e.getValue(), s);
        }
      }
      return toByteArray(wb);
    } catch (IOException ex) {
      log.error("Failed to generate DPR costing Excel", ex);
      throw new RuntimeException("Failed to generate DPR costing workbook", ex);
    }
  }

  private void writeSheet(XSSFWorkbook wb, String sheetName, String projectName,
                          List<Block> blocks, Styles s) {
    XSSFSheet sh = wb.createSheet(sheetName);

    // Title band.
    XSSFRow r0 = sh.createRow(0);
    setText(r0, 0, "Project : " + (projectName == null ? "" : projectName), s.title);
    sh.addMergedRegion(new CellRangeAddress(0, 0, 0, LAST_COL));
    XSSFRow r1 = sh.createRow(1);
    setText(r1, 0, "Daily Activity Costing", s.title);
    sh.addMergedRegion(new CellRangeAddress(1, 1, 0, LAST_COL));

    writeHeader(sh, s);

    int rowNum = FIRST_DATA_ROW;
    int sNo = 1;
    for (Block b : blocks) {
      rowNum = writeBlock(sh, rowNum, sNo++, b, s);
    }

    setColumnWidths(sh);
  }

  private void writeHeader(XSSFSheet sh, Styles s) {
    XSSFRow gr = sh.createRow(GROUP_HEADER_ROW);
    XSSFRow hr = sh.createRow(HEADER_ROW);

    // Scalar + progress columns: single label, vertically merged across the two header rows.
    String[] scalar = new String[LAST_COL + 1];
    scalar[C_SNO] = "S.N";
    scalar[C_DATE] = "Date";
    scalar[C_SITE] = "Site";
    scalar[C_LOCATION] = "Location";
    scalar[C_FROM] = "From";
    scalar[C_TO] = "To";
    scalar[C_SIDE] = "Side";
    scalar[C_ACTIVITY] = "Activity Code";
    scalar[C_UNIT] = "Unit";
    scalar[C_EXEC_QTY] = "Executed Qty";
    scalar[C_NAME] = "Name";
    scalar[C_REMARKS] = "Remarks";
    scalar[C_LENGTH] = "Length";
    scalar[C_TOTAL_QTY] = "Total Qty";
    scalar[C_PROG_QTY] = "Progress Qty";
    scalar[C_PROG_PCT] = "Progress %";
    scalar[C_PROG_LENGTH] = "Progress Length";
    for (int c = 0; c <= LAST_COL; c++) {
      if (scalar[c] != null) {
        setText(gr, c, scalar[c], s.headerCenter);
        hr.createCell(c).setCellStyle(s.headerCenter);
        sh.addMergedRegion(new CellRangeAddress(GROUP_HEADER_ROW, HEADER_ROW, c, c));
      }
    }

    // Resource groups: merged banner on the group row, per-column labels below.
    group(sh, gr, hr, s, "Manpower", C_MP_CATEGORY, C_MP_COST,
        new String[] {"Category", "Nr", "Rate", "Cost"});
    group(sh, gr, hr, s, "PmV", C_PMV_DETAIL, C_PMV_COST,
        new String[] {"Detail", "Nr", "Rate", "Cost"});
    group(sh, gr, hr, s, "Material", C_MAT_DESC, C_MAT_COST,
        new String[] {"Description", "Unit", "Quantity", "Rate", "Cost"});
    group(sh, gr, hr, s, "Subcontract", C_SC_NAME, C_SC_COST,
        new String[] {"Name", "Work Description", "Unit", "Quantity", "Rate", "Cost"});
  }

  private void group(XSSFSheet sh, XSSFRow gr, XSSFRow hr, Styles s,
                     String label, int firstCol, int lastCol, String[] subLabels) {
    setText(gr, firstCol, label, s.headerCenter);
    for (int c = firstCol + 1; c <= lastCol; c++) {
      gr.createCell(c).setCellStyle(s.headerCenter);
    }
    sh.addMergedRegion(new CellRangeAddress(GROUP_HEADER_ROW, GROUP_HEADER_ROW, firstCol, lastCol));
    for (int i = 0; i < subLabels.length; i++) {
      setText(hr, firstCol + i, subLabels[i], s.headerCenter);
    }
  }

  private int writeBlock(XSSFSheet sh, int startRow, int sNo, Block b, Styles s) {
    List<Manpower> mp = b.manpower();
    List<Pmv> pmv = b.pmv();
    List<Material> mat = b.material();
    List<SubContract> sub = b.subContract();
    int height = Math.max(1, Math.max(Math.max(mp.size(), pmv.size()), Math.max(mat.size(), sub.size())));

    for (int i = 0; i < height; i++) {
      XSSFRow row = sh.createRow(startRow + i);

      if (i == 0) {
        setNumber(row, C_SNO, sNo, s.intCenter);
        setDate(row, C_DATE, b.date(), s.date);
        setText(row, C_SITE, b.site(), s.plain);
        setText(row, C_LOCATION, b.location(), s.plain);
        setLong(row, C_FROM, b.chainageFrom(), s.chainage);
        setLong(row, C_TO, b.chainageTo(), s.chainage);
        setText(row, C_SIDE, b.side(), s.plain);
        setText(row, C_ACTIVITY, b.activityCode(), s.plain);
        setText(row, C_UNIT, b.unit(), s.plain);
        setNum(row, C_EXEC_QTY, b.executedQty(), s.qty);
        setText(row, C_NAME, b.supervisorName(), s.plain);
        setText(row, C_REMARKS, b.remarks(), s.plain);

        BigDecimal length = (b.chainageFrom() != null && b.chainageTo() != null)
            ? BigDecimal.valueOf(b.chainageTo() - b.chainageFrom()) : null;
        BigDecimal totalQty = b.totalQty();
        BigDecimal progQty = b.executedQty();
        BigDecimal progPct = (progQty != null && totalQty != null && totalQty.signum() != 0)
            ? progQty.divide(totalQty, 6, RoundingMode.HALF_UP) : null;
        BigDecimal progLen = (progPct != null && length != null) ? progPct.multiply(length) : null;
        setNum(row, C_LENGTH, length, s.qty);
        setNum(row, C_TOTAL_QTY, totalQty, s.qty);
        setNum(row, C_PROG_QTY, progQty, s.qty);
        setNum(row, C_PROG_PCT, progPct, s.pct);
        setNum(row, C_PROG_LENGTH, progLen, s.qty);
      }

      if (i < mp.size()) {
        Manpower m = mp.get(i);
        setText(row, C_MP_CATEGORY, m.category(), s.plain);
        setNum(row, C_MP_NR, m.nr(), s.qty);
        setNum(row, C_MP_RATE, m.rate(), s.money);
        setNum(row, C_MP_COST, m.cost(), s.money);
      }
      if (i < pmv.size()) {
        Pmv p = pmv.get(i);
        setText(row, C_PMV_DETAIL, p.detail(), s.plain);
        setNum(row, C_PMV_NR, p.nr(), s.qty);
        setNum(row, C_PMV_RATE, p.rate(), s.money);
        setNum(row, C_PMV_COST, p.cost(), s.money);
      }
      if (i < mat.size()) {
        Material m = mat.get(i);
        setText(row, C_MAT_DESC, m.description(), s.plain);
        setText(row, C_MAT_UNIT, m.unit(), s.plain);
        setNum(row, C_MAT_QTY, m.quantity(), s.qty);
        setNum(row, C_MAT_RATE, m.rate(), s.money);
        setNum(row, C_MAT_COST, m.cost(), s.money);
      }
      if (i < sub.size()) {
        SubContract c = sub.get(i);
        setText(row, C_SC_NAME, c.name(), s.plain);
        setText(row, C_SC_WORK, c.workDescription(), s.plain);
        setText(row, C_SC_UNIT, c.unit(), s.plain);
        setNum(row, C_SC_QTY, c.quantity(), s.qty);
        setNum(row, C_SC_RATE, c.rate(), s.money);
        setNum(row, C_SC_COST, c.cost(), s.money);
      }

      // Border every cell across the grid — fill any column left empty on this row.
      for (int c = 0; c <= LAST_COL; c++) {
        if (row.getCell(c) == null) row.createCell(c).setCellStyle(s.plain);
      }
    }
    return startRow + height;
  }

  // ── Cell helpers ─────────────────────────────────────────────────────────────────────────────
  private static void setText(XSSFRow row, int col, String value, CellStyle style) {
    XSSFCell c = row.createCell(col, CellType.STRING);
    c.setCellValue(value == null ? "" : value);
    if (style != null) c.setCellStyle(style);
  }

  private static void setNumber(XSSFRow row, int col, int value, CellStyle style) {
    XSSFCell c = row.createCell(col, CellType.NUMERIC);
    c.setCellValue(value);
    if (style != null) c.setCellStyle(style);
  }

  private static void setNum(XSSFRow row, int col, BigDecimal value, CellStyle style) {
    XSSFCell c = row.createCell(col, value == null ? CellType.BLANK : CellType.NUMERIC);
    if (value != null) c.setCellValue(value.doubleValue());
    if (style != null) c.setCellStyle(style);
  }

  private static void setLong(XSSFRow row, int col, Long value, CellStyle style) {
    XSSFCell c = row.createCell(col, value == null ? CellType.BLANK : CellType.NUMERIC);
    if (value != null) c.setCellValue(value.doubleValue());
    if (style != null) c.setCellStyle(style);
  }

  private static void setDate(XSSFRow row, int col, LocalDate value, CellStyle style) {
    XSSFCell c = row.createCell(col, value == null ? CellType.BLANK : CellType.NUMERIC);
    if (value != null) c.setCellValue(java.sql.Date.valueOf(value));
    if (style != null) c.setCellStyle(style);
  }

  private static void setColumnWidths(XSSFSheet sh) {
    int[] w = new int[LAST_COL + 1];
    w[C_SNO] = 1400;
    w[C_DATE] = 2800;
    w[C_SITE] = 3000;
    w[C_LOCATION] = 3400;
    w[C_FROM] = 2800;
    w[C_TO] = 2800;
    w[C_SIDE] = 2000;
    w[C_ACTIVITY] = 3000;
    w[C_UNIT] = 2000;
    w[C_EXEC_QTY] = 3000;
    w[C_NAME] = 4200;
    w[C_MP_CATEGORY] = 3600;
    w[C_MP_NR] = 1600;
    w[C_MP_RATE] = 2600;
    w[C_MP_COST] = 3000;
    w[C_PMV_DETAIL] = 4200;
    w[C_PMV_NR] = 1600;
    w[C_PMV_RATE] = 2600;
    w[C_PMV_COST] = 3000;
    w[C_MAT_DESC] = 4200;
    w[C_MAT_UNIT] = 2000;
    w[C_MAT_QTY] = 2600;
    w[C_MAT_RATE] = 2600;
    w[C_MAT_COST] = 3000;
    w[C_SC_NAME] = 4000;
    w[C_SC_WORK] = 4000;
    w[C_SC_UNIT] = 2000;
    w[C_SC_QTY] = 2600;
    w[C_SC_RATE] = 2600;
    w[C_SC_COST] = 3000;
    w[C_REMARKS] = 4000;
    w[C_LENGTH] = 2600;
    w[C_TOTAL_QTY] = 3000;
    w[C_PROG_QTY] = 3000;
    w[C_PROG_PCT] = 2600;
    w[C_PROG_LENGTH] = 3000;
    for (int i = 0; i <= LAST_COL; i++) sh.setColumnWidth(i, w[i]);
  }

  private static byte[] toByteArray(XSSFWorkbook wb) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    wb.write(baos);
    return baos.toByteArray();
  }

  /** Per-workbook style cache (Excel caps styles at 64k). */
  private static final class Styles {
    final CellStyle title;
    final CellStyle headerCenter;
    final CellStyle plain;
    final CellStyle intCenter;
    final CellStyle date;
    final CellStyle chainage;
    final CellStyle qty;
    final CellStyle money;
    final CellStyle pct;

    Styles(XSSFWorkbook wb) {
      DataFormat df = wb.createDataFormat();

      Font fontPlain = wb.createFont();
      Font fontBold = wb.createFont();
      fontBold.setBold(true);
      Font fontTitle = wb.createFont();
      fontTitle.setBold(true);
      fontTitle.setFontHeightInPoints((short) 12);

      title = wb.createCellStyle();
      title.setFont(fontTitle);
      title.setAlignment(HorizontalAlignment.CENTER);

      headerCenter = wb.createCellStyle();
      headerCenter.setFont(fontBold);
      headerCenter.setAlignment(HorizontalAlignment.CENTER);
      headerCenter.setVerticalAlignment(VerticalAlignment.CENTER);
      headerCenter.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      headerCenter.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      headerCenter.setWrapText(true);
      borderAll(headerCenter);

      plain = wb.createCellStyle();
      plain.setFont(fontPlain);
      plain.setVerticalAlignment(VerticalAlignment.CENTER);
      borderAll(plain);

      intCenter = wb.createCellStyle();
      intCenter.setFont(fontPlain);
      intCenter.setAlignment(HorizontalAlignment.CENTER);
      intCenter.setVerticalAlignment(VerticalAlignment.CENTER);
      borderAll(intCenter);

      date = wb.createCellStyle();
      date.setFont(fontPlain);
      date.setDataFormat(df.getFormat("d-mmm-yy"));
      date.setVerticalAlignment(VerticalAlignment.CENTER);
      borderAll(date);

      chainage = wb.createCellStyle();
      chainage.setFont(fontPlain);
      chainage.setDataFormat(df.getFormat("0\\+000.00"));
      chainage.setAlignment(HorizontalAlignment.RIGHT);
      borderAll(chainage);

      qty = wb.createCellStyle();
      qty.setFont(fontPlain);
      qty.setDataFormat(df.getFormat("#,##0.00"));
      qty.setAlignment(HorizontalAlignment.RIGHT);
      borderAll(qty);

      money = wb.createCellStyle();
      money.setFont(fontPlain);
      money.setDataFormat(df.getFormat("#,##0.00"));
      money.setAlignment(HorizontalAlignment.RIGHT);
      borderAll(money);

      pct = wb.createCellStyle();
      pct.setFont(fontPlain);
      pct.setDataFormat(df.getFormat("0.00%"));
      pct.setAlignment(HorizontalAlignment.RIGHT);
      borderAll(pct);
    }

    private static void borderAll(CellStyle st) {
      st.setBorderBottom(BorderStyle.THIN);
      st.setBorderTop(BorderStyle.THIN);
      st.setBorderLeft(BorderStyle.THIN);
      st.setBorderRight(BorderStyle.THIN);
    }
  }
}
