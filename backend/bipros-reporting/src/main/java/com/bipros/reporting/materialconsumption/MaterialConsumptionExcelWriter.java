package com.bipros.reporting.materialconsumption;

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
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Single-sheet Excel writer for the Material Consumption Report. Column layout mirrors the
 * filter / table on the frontend page so users can re-open the export and re-create the same
 * view without surprises.
 */
@Component
@Slf4j
public class MaterialConsumptionExcelWriter {

  private static final String[] HEADERS = {
      "Date Range", "WBS", "Activity", "Supervisor", "Storekeeper",
      "Material", "Unit", "Planned Qty", "Issued Qty", "Consumed Qty",
      "Balance Qty", "Wastage %", "Unit Rate", "Planned Cost", "Actual Cost",
      "Variance", "Variance %", "Alerts"
  };

  public byte[] write(MaterialConsumptionReportResponse report) {
    try (XSSFWorkbook wb = new XSSFWorkbook()) {
      Styles s = new Styles(wb);
      XSSFSheet sh = wb.createSheet("Material Consumption");

      // Title / window band.
      XSSFRow title = sh.createRow(0);
      setText(title, 0, "Material Consumption Report — "
          + safeDate(report.from()) + " to " + safeDate(report.to())
          + (report.groupBy() != null ? "  (grouped by " + report.groupBy() + ")" : ""),
          s.title);

      // Header row.
      XSSFRow header = sh.createRow(2);
      for (int c = 0; c < HEADERS.length; c++) {
        setText(header, c, HEADERS[c], s.headerCenter);
      }

      // Body.
      int rowNum = 3;
      List<MaterialConsumptionRow> rows = report.rows();
      if (rows != null) {
        for (MaterialConsumptionRow r : rows) {
          XSSFRow body = sh.createRow(rowNum++);
          setText(body, 0, formatDateRange(r), s.plain);
          setText(body, 1, r.wbsName(), s.plain);
          setText(body, 2, r.activityName(), s.plain);
          setText(body, 3, r.supervisorName(), s.plain);
          setText(body, 4, r.storekeeperName(), s.plain);
          setText(body, 5, r.materialName(), s.plain);
          setText(body, 6, r.unit(), s.plain);
          setBigDecimal(body, 7, r.plannedQty(), s.numCell);
          setBigDecimal(body, 8, r.issuedQty(), s.numCell);
          setBigDecimal(body, 9, r.consumedQty(), s.numCell);
          setBigDecimal(body, 10, r.balanceQty(), s.numCell);
          setBigDecimal(body, 11, r.wastagePercent(), s.numCell);
          setBigDecimal(body, 12, r.unitRate(), s.numCell);
          setBigDecimal(body, 13, r.plannedCost(), s.numCell);
          setBigDecimal(body, 14, r.actualCost(), s.numCell);
          setBigDecimal(body, 15, r.variance(), s.numCell);
          setBigDecimal(body, 16, r.variancePercent(), s.pctCell);
          setText(body, 17, formatAlerts(r.alerts()),
              r.alerts() != null && !r.alerts().isEmpty() ? s.alertCell : s.plain);
        }
      }

      // Totals row.
      if (report.totals() != null && !report.totals().isEmpty()) {
        rowNum++; // spacer
        XSSFRow totalsRow = sh.createRow(rowNum++);
        setText(totalsRow, 0, "TOTALS", s.groupBold);
        setBigDecimal(totalsRow, 11, report.totals().get("wastagePercent_avg"), s.numCell);
        setBigDecimal(totalsRow, 13, report.totals().get("plannedCost"), s.numCell);
        setBigDecimal(totalsRow, 14, report.totals().get("actualCost"), s.numCell);
        setBigDecimal(totalsRow, 15, report.totals().get("variance"), s.numCell);
      }

      // Alert summary.
      if (report.alertCounts() != null && !report.alertCounts().isEmpty()) {
        rowNum++; // spacer
        XSSFRow alertHead = sh.createRow(rowNum++);
        setText(alertHead, 0, "Alert summary", s.groupBold);
        for (Map.Entry<String, Integer> e : report.alertCounts().entrySet()) {
          XSSFRow ar = sh.createRow(rowNum++);
          setText(ar, 0, e.getKey(), s.plain);
          setBigDecimal(ar, 1, BigDecimal.valueOf(e.getValue()), s.numCell);
        }
      }

      setColumnWidths(sh, new int[] {
          5200, 5200, 6200, 4400, 4400, 6200, 1800, 2800, 2800, 3000,
          3000, 2400, 3000, 3400, 3400, 3400, 2600, 6000
      });

      return toByteArray(wb);
    } catch (IOException e) {
      log.error("Failed to generate Material Consumption workbook", e);
      throw new RuntimeException("Failed to generate Material Consumption workbook", e);
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────

  private static String formatDateRange(MaterialConsumptionRow r) {
    if (r.fromDate() == null && r.toDate() == null) return "";
    if (r.fromDate() != null && r.fromDate().equals(r.toDate())) return r.fromDate().toString();
    return safeDate(r.fromDate()) + " to " + safeDate(r.toDate());
  }

  private static String safeDate(LocalDate d) {
    return d == null ? "" : d.toString();
  }

  private static String formatAlerts(List<String> alerts) {
    if (alerts == null || alerts.isEmpty()) return "";
    return String.join(", ", alerts);
  }

  private static void setText(XSSFRow row, int col, String value, CellStyle style) {
    XSSFCell c = row.createCell(col, CellType.STRING);
    c.setCellValue(value == null ? "" : value);
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

  /** Per-workbook style cache. Mirror of {@code CapacityUtilizationExcelWriter.Styles}. */
  private static final class Styles {
    final CellStyle plain;
    final CellStyle title;
    final CellStyle headerCenter;
    final CellStyle groupBold;
    final CellStyle numCell;
    final CellStyle pctCell;
    final CellStyle alertCell;

    Styles(XSSFWorkbook wb) {
      DataFormat df = wb.createDataFormat();

      Font fontPlain = wb.createFont();
      Font fontBold = wb.createFont();
      fontBold.setBold(true);
      Font fontTitle = wb.createFont();
      fontTitle.setBold(true);
      fontTitle.setFontHeightInPoints((short) 12);

      plain = wb.createCellStyle();
      plain.setFont(fontPlain);
      borderAll(plain);

      title = wb.createCellStyle();
      title.setFont(fontTitle);

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
      pctCell.setDataFormat(df.getFormat("0.00%"));
      pctCell.setAlignment(HorizontalAlignment.RIGHT);
      borderAll(pctCell);

      alertCell = wb.createCellStyle();
      alertCell.setFont(fontBold);
      alertCell.setFillForegroundColor(IndexedColors.ROSE.getIndex());
      alertCell.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      borderAll(alertCell);
    }

    private static void borderAll(CellStyle s) {
      s.setBorderBottom(BorderStyle.THIN);
      s.setBorderTop(BorderStyle.THIN);
      s.setBorderLeft(BorderStyle.THIN);
      s.setBorderRight(BorderStyle.THIN);
    }
  }
}
