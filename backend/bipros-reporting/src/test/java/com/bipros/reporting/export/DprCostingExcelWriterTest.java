package com.bipros.reporting.export;

import com.bipros.reporting.application.dto.DprCostingReport;
import com.bipros.reporting.application.dto.DprCostingReport.Block;
import com.bipros.reporting.application.dto.DprCostingReport.Manpower;
import com.bipros.reporting.infrastructure.export.DprCostingExcelWriter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DprCostingExcelWriterTest {

  private final DprCostingExcelWriter writer = new DprCostingExcelWriter();

  /** Mirrors the user's sample DPR (2026-06-27, activity 2.6.6(i), two manpower lines). */
  @Test
  void writesMonthlySheetWithComputedCostsAndProgress() throws Exception {
    Block block = new Block(
        LocalDate.of(2026, 6, 27),
        null, "Near Hairpin bend", 33950L, 34050L, "CENTER",
        "2.6.6(i)", "cum", new BigDecimal("1800"), null, "EMP-012 — Vijay Kumar",
        new BigDecimal("2500"),   // Total Qty (BOQ qty_executed_to_date) → drives Progress % / Length
        List.of(
            new Manpower("Helper / Handyman", new BigDecimal("5"), new BigDecimal("180"), new BigDecimal("900")),
            new Manpower("Supervisor", new BigDecimal("1"), new BigDecimal("45"), new BigDecimal("45"))),
        List.of(), List.of(), List.of());

    byte[] bytes = writer.generate(new DprCostingReport("SC 180 — Khasab", List.of(block)));

    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      assertThat(wb.getNumberOfSheets()).isEqualTo(1);
      Sheet sh = wb.getSheetAt(0);
      assertThat(sh.getSheetName()).isEqualTo("Jun 2026");

      // Group-header row (index 2): "Name" is now a standalone scalar; "Manpower" banner starts at Category.
      assertThat(sh.getRow(2).getCell(10).getStringCellValue()).isEqualTo("Name");
      assertThat(sh.getRow(2).getCell(11).getStringCellValue()).isEqualTo("Manpower");
      assertThat(sh.getRow(2).getCell(32).getStringCellValue()).isEqualTo("Total Qty");
      // Material group now carries a Quantity column (Description·Unit·Quantity·Rate·Cost).
      assertThat(sh.getRow(3).getCell(21).getStringCellValue()).isEqualTo("Quantity");
      // Resource sub-labels sit on the lower header row (index 3); Manpower = Category·Nr·Rate·Cost.
      var header = sh.getRow(3);
      assertThat(header.getCell(11).getStringCellValue()).isEqualTo("Category");
      assertThat(header.getCell(12).getStringCellValue()).isEqualTo("Nr");
      assertThat(header.getCell(14).getStringCellValue()).isEqualTo("Cost");

      // First data row (index 4): scalars + Name + first manpower line + progress.
      var r1 = sh.getRow(4);
      assertThat(r1.getCell(7).getStringCellValue()).isEqualTo("2.6.6(i)");   // Activity Code
      assertThat(r1.getCell(9).getNumericCellValue()).isEqualTo(1800.0);       // Executed Qty
      assertThat(r1.getCell(10).getStringCellValue()).isEqualTo("EMP-012 — Vijay Kumar"); // Name (standalone)
      assertThat(r1.getCell(11).getStringCellValue()).isEqualTo("Helper / Handyman"); // Category
      assertThat(r1.getCell(12).getNumericCellValue()).isEqualTo(5.0);         // Nr
      assertThat(r1.getCell(14).getNumericCellValue()).isEqualTo(900.0);       // Cost = Nr × Rate
      assertThat(r1.getCell(31).getNumericCellValue()).isEqualTo(100.0);       // Length = 34050-33950
      assertThat(r1.getCell(32).getNumericCellValue()).isEqualTo(2500.0);      // Total Qty
      assertThat(r1.getCell(34).getNumericCellValue()).isEqualTo(0.72);        // Progress % = 1800/2500
      assertThat(r1.getCell(35).getNumericCellValue()).isEqualTo(72.0);        // Progress Length = 0.72 × 100

      // Empty cells across the grid are still bordered (e.g. the PmV Detail column, no equipment here).
      assertThat(r1.getCell(15)).isNotNull();
      assertThat(r1.getCell(15).getCellStyle().getBorderBottom())
          .isNotEqualTo(org.apache.poi.ss.usermodel.BorderStyle.NONE);

      // Second manpower line stacks directly on the next row — no spacer.
      var r2 = sh.getRow(5);
      assertThat(r2.getCell(11).getStringCellValue()).isEqualTo("Supervisor");
      assertThat(r2.getCell(14).getNumericCellValue()).isEqualTo(45.0);
      // Single block of height 2 ⇒ no third data row.
      assertThat(sh.getRow(6)).isNull();
    }
  }

  @Test
  void emptyReportProducesValidWorkbookWithSingleSheet() throws Exception {
    byte[] bytes = writer.generate(new DprCostingReport("SC 180", List.of()));
    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      assertThat(wb.getNumberOfSheets()).isEqualTo(1);
      assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("DPR");
    }
  }
}
