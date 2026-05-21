package com.bipros.dbs.export;

import com.bipros.dbs.api.dto.CmShiftCount;
import com.bipros.dbs.api.dto.CumulativeDaysResponse;
import com.bipros.dbs.api.dto.CumulativeDaysResponse.CumulativeEquipmentDays;
import com.bipros.dbs.api.dto.CumulativeDaysResponse.CumulativeManpowerDays;
import com.bipros.dbs.api.dto.DbsProjectDayResponse;
import com.bipros.dbs.api.dto.EquipmentRegisterResponse;
import com.bipros.dbs.api.dto.EquipmentRegisterTypeRow;
import com.bipros.dbs.service.DbsQueryService;
import com.bipros.dbs.service.RegisterAggregationService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Phase 9 snapshot-style test for the three new sheets produced by
 * {@link DbsExcelWriter#writePmReport(UUID, LocalDate)}.
 *
 * <p>The test stubs the query / register services with a minimal dataset (one CM,
 * one equipment type, one trade), generates the workbook, then opens it via
 * {@link WorkbookFactory} and asserts the three new sheets are present with the
 * expected header rows. We deliberately do NOT diff the entire workbook — that
 * would be brittle across POI version bumps; we lock in the sheet contract only.
 */
@ExtendWith(MockitoExtension.class)
class DbsExcelWriterRegisterSheetsTest {

    @Mock private DbsQueryService queryService;
    @Mock private RegisterAggregationService registerService;

    @InjectMocks private DbsExcelWriter writer;

    private final UUID projectId = UUID.randomUUID();
    private final UUID cmId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 5, 18);

    @Test
    @DisplayName("writePmReport: emits 'MP & Eqpt Summary', 'Plant Summary', 'Eqpmnt & MP Days' sheets")
    void pmReport_addsRegisterAndCumulativeSheets() throws Exception {
        // Minimal project + roster: zero engineers / supervisors so the workbook
        // only carries Summary-Financial + the three new register sheets. Keeps
        // the test focused on the Phase 9 additions.
        when(queryService.getProjectDay(eq(projectId), eq(date)))
            .thenReturn(new DbsProjectDayResponse(
                /*id*/ UUID.randomUUID(),
                /*projectId*/ projectId,
                /*reportDate*/ date,
                /*engineerIds*/ List.of(),
                /*supervisorCount*/ 0,
                /*dprCount*/ 0,
                /*materialAmount*/ BigDecimal.ZERO,
                /*manpowerAmount*/ BigDecimal.ZERO,
                /*adminAmount*/ BigDecimal.ZERO,
                /*machineryAmount*/ BigDecimal.ZERO,
                /*fuelAmount*/ BigDecimal.ZERO,
                /*subcontractAmount*/ BigDecimal.ZERO,
                /*generalExpenseAmount*/ BigDecimal.ZERO,
                /*generalExpenseMonthlyTotal*/ BigDecimal.ZERO,
                /*generalExpenseLinesJson*/ "[]",
                /*boqForTheDayAmount*/ BigDecimal.ZERO,
                /*boqPlannedAmount*/ BigDecimal.ZERO,
                /*boqAchievedAmount*/ BigDecimal.ZERO,
                /*directCost*/ BigDecimal.ZERO,
                /*prelimCost*/ BigDecimal.ZERO,
                /*totalCostInclPrelims*/ BigDecimal.ZERO,
                /*pctAchieved*/ BigDecimal.ZERO,
                /*totalExpense*/ BigDecimal.ZERO,
                /*totalIncome*/ BigDecimal.ZERO,
                /*contribution*/ BigDecimal.ZERO,
                /*contributionPct*/ BigDecimal.ZERO,
                /*cumulativeExpense*/ null,
                /*cumulativeIncome*/ null,
                /*cumulativeContribution*/ null,
                /*recomputedAt*/ null,
                /*alerts*/ List.of()));
        when(queryService.listSupervisorsForDay(eq(projectId), eq(date)))
            .thenReturn(List.of());

        // Equipment register: one Grader under one CM, day shift only.
        CmShiftCount perCm = new CmShiftCount(cmId, "T Swamy",
            /*day*/ 2, /*night*/ 1, /*total*/ 3);
        EquipmentRegisterResponse equipment = new EquipmentRegisterResponse(
            date,
            List.of(new EquipmentRegisterTypeRow("Grader", List.of(perCm), 2, 1, 3)));
        when(registerService.getEquipmentRegister(eq(projectId), eq(date), any()))
            .thenReturn(equipment);

        // Cumulative: one equipment type, one trade.
        CumulativeDaysResponse cumulative = new CumulativeDaysResponse(
            date,
            List.of(new CumulativeEquipmentDays("Grader", 5L)),
            List.of(new CumulativeManpowerDays("Mason", 12L)));
        when(registerService.cumulative(eq(projectId), eq(date), any()))
            .thenReturn(cumulative);

        byte[] bytes = writer.writePmReport(projectId, date);
        assertThat(bytes).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getSheet("MP & Eqpt Summary"))
                .as("Equipment Register sheet present").isNotNull();
            assertThat(wb.getSheet("Plant Summary"))
                .as("Plant Summary sheet present").isNotNull();
            assertThat(wb.getSheet("Eqpmnt & MP Days"))
                .as("Cumulative Days sheet present").isNotNull();

            // Equipment register header — row 3 (after title, meta, spacer).
            Sheet eq = wb.getSheet("MP & Eqpt Summary");
            Row eqHeader = findHeaderRowStartingWith(eq, "SL No");
            assertThat(eqHeader).isNotNull();
            assertThat(eqHeader.getCell(0).getStringCellValue()).isEqualTo("SL No");
            assertThat(eqHeader.getCell(1).getStringCellValue()).isEqualTo("Equipment");
            assertThat(eqHeader.getCell(2).getStringCellValue()).isEqualTo("Total");
            assertThat(eqHeader.getCell(3).getStringCellValue()).isEqualTo("T Swamy Day");
            assertThat(eqHeader.getCell(4).getStringCellValue()).isEqualTo("T Swamy Night");
            assertThat(eqHeader.getCell(5).getStringCellValue()).isEqualTo("Off Road");

            // Plant Summary header.
            Sheet ps = wb.getSheet("Plant Summary");
            Row psHeader = findHeaderRowStartingWith(ps, "Equipment");
            assertThat(psHeader).isNotNull();
            assertThat(psHeader.getCell(0).getStringCellValue()).isEqualTo("Equipment");
            assertThat(psHeader.getCell(1).getStringCellValue()).isEqualTo("Variation");
            assertThat(psHeader.getCell(2).getStringCellValue()).isEqualTo("Available");
            assertThat(psHeader.getCell(3).getStringCellValue()).isEqualTo("Total as per Site");
            assertThat(psHeader.getCell(4).getStringCellValue()).isEqualTo("T Swamy No's/Day");
            assertThat(psHeader.getCell(5).getStringCellValue()).isEqualTo("T Swamy Total");

            // Cumulative Days — two "Resource | Cumulative Days" headers.
            Sheet cd = wb.getSheet("Eqpmnt & MP Days");
            int headerCount = 0;
            for (int i = 0; i <= cd.getLastRowNum(); i++) {
                Row r = cd.getRow(i);
                if (r == null) continue;
                if (r.getCell(0) != null
                    && "Resource".equals(safeStr(r.getCell(0).toString()))
                    && r.getCell(1) != null
                    && "Cumulative Days".equals(safeStr(r.getCell(1).toString()))) {
                    headerCount++;
                }
            }
            assertThat(headerCount).as("Cumulative sheet has equipment + manpower headers").isEqualTo(2);
        }
    }

    private static Row findHeaderRowStartingWith(Sheet sheet, String firstCell) {
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            Row r = sheet.getRow(i);
            if (r == null) continue;
            if (r.getCell(0) != null && firstCell.equals(safeStr(r.getCell(0).toString()))) {
                return r;
            }
        }
        return null;
    }

    private static String safeStr(String s) {
        return s == null ? null : s.trim();
    }
}
