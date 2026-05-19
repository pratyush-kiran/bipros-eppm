package com.bipros.dbs.export;

import com.bipros.dbs.api.dto.CmShiftCount;
import com.bipros.dbs.api.dto.CumulativeDaysResponse;
import com.bipros.dbs.api.dto.DbsEngineerDayResponse;
import com.bipros.dbs.api.dto.DbsProjectDayResponse;
import com.bipros.dbs.api.dto.DbsSectionLineDto;
import com.bipros.dbs.api.dto.DbsSupervisorDayResponse;
import com.bipros.dbs.api.dto.DbsSupervisorSummaryDto;
import com.bipros.dbs.api.dto.EquipmentRegisterResponse;
import com.bipros.dbs.api.dto.EquipmentRegisterTypeRow;
import com.bipros.dbs.api.dto.ManpowerRegisterResponse;
import com.bipros.dbs.api.dto.ManpowerRegisterTradeRow;
import com.bipros.dbs.service.DbsQueryService;
import com.bipros.dbs.service.RegisterAggregationService;
import lombok.RequiredArgsConstructor;
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
import org.apache.poi.ss.util.WorkbookUtil;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Writes the Daily Balance Sheet workbook — one supervisor sheet per supervisor with the
 * six section blocks + BOQ block (mirrors the client's Anbazhagan-TS sheet layout), an
 * Engineer rollup (PRE) sheet, and a Summary-Financial sheet for the PM view.
 *
 * <p>The cell-style cache + helpers mirror
 * {@code com.bipros.reporting.infrastructure.export.CapacityUtilizationExcelWriter}; we
 * duplicate the small bits we need here rather than depending on the reporting module to
 * avoid a module cycle (reporting already depends on several other domain modules).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DbsExcelWriter {

    private final DbsQueryService queryService;
    private final RegisterAggregationService registerService;

    // ── Public API ─────────────────────────────────────────────────────────────────────────

    /**
     * Build the full PM workbook: Summary-Financial sheet + one PRE sheet per engineer +
     * one Costing-Report sheet per supervisor present for the day.
     */
    public byte[] writePmReport(UUID projectId, LocalDate date) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);

            DbsProjectDayResponse projectDay = queryService.getProjectDay(projectId, date);
            List<DbsSupervisorSummaryDto> supervisorRoster =
                queryService.listSupervisorsForDay(projectId, date);

            // 1. Summary-Financial sheet (PM-level): per-engineer rows + project totals
            List<DbsEngineerDayResponse> engineerDays = new ArrayList<>();
            for (UUID eid : nz(projectDay.engineerIds())) {
                engineerDays.add(queryService.getEngineerDay(projectId, eid, date));
            }
            writeSummaryFinancialSheet(wb, "Summary-Financial", projectDay, engineerDays, date, s);

            // 2. PRE sheet (Engineer rollup) — one per engineer
            for (DbsEngineerDayResponse eng : engineerDays) {
                String sheetName = safeSheetName(
                    "PRE-" + shortUuid(eng.engineerUserId()),
                    wb);
                writeEngineerSheet(wb, sheetName, eng, date, s);
            }

            // 3. One sheet per supervisor with the full six-section + BOQ layout
            Set<UUID> seen = new HashSet<>();
            for (DbsSupervisorSummaryDto roster : supervisorRoster) {
                UUID supId = roster.supervisorUserId();
                if (!seen.add(supId == null ? new UUID(0, 0) : supId)) continue;
                DbsSupervisorDayResponse sup = queryService.getSupervisorDay(projectId, supId, date);
                String label = roster.supervisorName() != null && !roster.supervisorName().isBlank()
                    ? roster.supervisorName()
                    : shortUuid(supId);
                String sheetName = safeSheetName(label, wb);
                writeSupervisorSheet(wb, sheetName, sup, date, s);
            }

            // 4. Equipment & Manpower Register sheets (Phase 9). These match the
            //    "MP & Eqpt Summary", "Plant Summary", and "Eqpmnt & MP Days" sheets
            //    of the client's Excel template.
            EquipmentRegisterResponse equipmentRegister =
                registerService.getEquipmentRegister(projectId, date, null);
            writeEquipmentRegisterSheet(wb, equipmentRegister, s);
            writePlantSummarySheet(wb, equipmentRegister, s);

            CumulativeDaysResponse cumulative = registerService.cumulative(projectId, date, null);
            writeCumulativeDaysSheet(wb, cumulative, s);

            return toByteArray(wb);
        } catch (IOException ex) {
            log.error("Failed to write DBS PM workbook for project {} date {}", projectId, date, ex);
            throw new RuntimeException("Failed to generate DBS workbook", ex);
        }
    }

    /**
     * Build a single-supervisor workbook (one sheet) for download.
     */
    public byte[] writeSupervisorReport(UUID projectId, UUID supervisorUserId, LocalDate date) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);
            DbsSupervisorDayResponse sup = queryService.getSupervisorDay(projectId, supervisorUserId, date);
            String label = shortUuid(supervisorUserId);
            writeSupervisorSheet(wb, safeSheetName(label, wb), sup, date, s);
            return toByteArray(wb);
        } catch (IOException ex) {
            log.error("Failed to write DBS Supervisor workbook for project {} sup {} date {}",
                projectId, supervisorUserId, date, ex);
            throw new RuntimeException("Failed to generate DBS workbook", ex);
        }
    }

    // ── Sheet: Summary-Financial (PM level) ────────────────────────────────────────────────

    private void writeSummaryFinancialSheet(
        XSSFWorkbook wb, String sheetName,
        DbsProjectDayResponse projectDay,
        List<DbsEngineerDayResponse> engineerDays,
        LocalDate date,
        Styles s) {

        XSSFSheet sh = wb.createSheet(sheetName);
        int rowNum = 0;

        XSSFRow title = sh.createRow(rowNum++);
        setText(title, 0, "Summary - Financial (PM Daily Balance Sheet)", s.title);
        sh.addMergedRegion(new CellRangeAddress(0, 0, 0, 7));

        XSSFRow meta = sh.createRow(rowNum++);
        setText(meta, 0, "Date :", s.bold);
        setText(meta, 1, date.toString(), s.plain);
        setText(meta, 3, "Project :", s.bold);
        setText(meta, 4, projectDay.projectId() == null ? "" : projectDay.projectId().toString(), s.plain);

        rowNum++; // spacer

        XSSFRow header = sh.createRow(rowNum++);
        setText(header, 0, "Engineer ID", s.headerCenter);
        setText(header, 1, "Plan Amount", s.headerCenter);
        setText(header, 2, "Achieved (Income)", s.headerCenter);
        setText(header, 3, "Cost (Expense)", s.headerCenter);
        setText(header, 4, "Cost %", s.headerCenter);
        setText(header, 5, "Contribution", s.headerCenter);
        setText(header, 6, "Contribution %", s.headerCenter);
        setText(header, 7, "Profit/Loss", s.headerCenter);

        for (DbsEngineerDayResponse eng : engineerDays) {
            XSSFRow row = sh.createRow(rowNum++);
            setText(row, 0, shortUuid(eng.engineerUserId()), s.plain);
            setBigDecimal(row, 1, nz(eng.boqPlannedAmount()), s.numCell);
            setBigDecimal(row, 2, nz(eng.totalIncome()), s.numCell);
            setBigDecimal(row, 3, nz(eng.totalExpense()), s.numCell);
            setBigDecimal(row, 4, ratio(eng.totalExpense(), eng.totalIncome()), s.pctCell);
            setBigDecimal(row, 5, nz(eng.contribution()), s.numCell);
            setBigDecimal(row, 6, nz(eng.contributionPct()), s.pctCell);
            BigDecimal pl = nz(eng.contribution());
            setText(row, 7,
                pl.signum() > 0 ? "Profit" : pl.signum() < 0 ? "Loss" : "Flat",
                s.plain);
        }

        // Totals row
        XSSFRow totalsRow = sh.createRow(rowNum++);
        setText(totalsRow, 0, "Project Totals", s.groupBold);
        setBigDecimal(totalsRow, 1, nz(projectDay.boqPlannedAmount()), s.groupBoldNum);
        setBigDecimal(totalsRow, 2, nz(projectDay.totalIncome()), s.groupBoldNum);
        setBigDecimal(totalsRow, 3, nz(projectDay.totalExpense()), s.groupBoldNum);
        setBigDecimal(totalsRow, 4, ratio(projectDay.totalExpense(), projectDay.totalIncome()), s.groupBoldPct);
        setBigDecimal(totalsRow, 5, nz(projectDay.contribution()), s.groupBoldNum);
        setBigDecimal(totalsRow, 6, nz(projectDay.contributionPct()), s.groupBoldPct);
        BigDecimal projPl = nz(projectDay.contribution());
        setText(totalsRow, 7,
            projPl.signum() > 0 ? "Profit" : projPl.signum() < 0 ? "Loss" : "Flat",
            s.groupBold);

        rowNum++; // spacer

        // Cumulative line (if available)
        if (projectDay.cumulativeExpense() != null
            || projectDay.cumulativeIncome() != null
            || projectDay.cumulativeContribution() != null) {
            XSSFRow cumHeader = sh.createRow(rowNum++);
            setText(cumHeader, 0, "Cumulative to Date", s.bold);
            XSSFRow cumRow = sh.createRow(rowNum++);
            setText(cumRow, 0, "Cumulative", s.plain);
            setBigDecimal(cumRow, 2, nz(projectDay.cumulativeIncome()), s.numCell);
            setBigDecimal(cumRow, 3, nz(projectDay.cumulativeExpense()), s.numCell);
            setBigDecimal(cumRow, 5, nz(projectDay.cumulativeContribution()), s.numCell);
        }

        setColumnWidths(sh, new int[] {
            8000, 4400, 4800, 4800, 2800, 4400, 3200, 3200
        });
    }

    // ── Sheet: PRE (Engineer rollup) ───────────────────────────────────────────────────────

    private void writeEngineerSheet(
        XSSFWorkbook wb, String sheetName, DbsEngineerDayResponse eng, LocalDate date, Styles s) {

        XSSFSheet sh = wb.createSheet(sheetName);
        int rowNum = 0;

        XSSFRow title = sh.createRow(rowNum++);
        setText(title, 0, "PRE — Engineer Daily Rollup", s.title);
        sh.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

        XSSFRow meta = sh.createRow(rowNum++);
        setText(meta, 0, "Engineer :", s.bold);
        setText(meta, 1, shortUuid(eng.engineerUserId()), s.plain);
        setText(meta, 2, "Date :", s.bold);
        setText(meta, 3, date.toString(), s.plain);

        rowNum++; // spacer

        XSSFRow header = sh.createRow(rowNum++);
        setText(header, 0, "Section", s.headerCenter);
        setText(header, 1, "Amount", s.headerCenter);

        rowNum = writeSectionTotalRow(sh, rowNum, "E. Material", eng.materialAmount(), s);
        rowNum = writeSectionTotalRow(sh, rowNum, "A. Manpower", eng.manpowerAmount(), s);
        rowNum = writeSectionTotalRow(sh, rowNum, "B. Catering / Admin", eng.adminAmount(), s);
        rowNum = writeSectionTotalRow(sh, rowNum, "C. Machinery", eng.machineryAmount(), s);
        rowNum = writeSectionTotalRow(sh, rowNum, "D. Fuel", eng.fuelAmount(), s);
        rowNum = writeSectionTotalRow(sh, rowNum, "F. SubContractor", eng.subcontractAmount(), s);

        rowNum++; // spacer
        XSSFRow boqHeader = sh.createRow(rowNum++);
        setText(boqHeader, 0, "BOQ Work", s.headerCenter);

        XSSFRow boqDay = sh.createRow(rowNum++);
        setText(boqDay, 0, "For the Day", s.plain);
        setBigDecimal(boqDay, 1, nz(eng.boqForTheDayAmount()), s.numCell);

        XSSFRow boqPlan = sh.createRow(rowNum++);
        setText(boqPlan, 0, "Planned Amount", s.plain);
        setBigDecimal(boqPlan, 1, nz(eng.boqPlannedAmount()), s.numCell);

        XSSFRow boqAch = sh.createRow(rowNum++);
        setText(boqAch, 0, "Achieved Amount", s.plain);
        setBigDecimal(boqAch, 1, nz(eng.boqAchievedAmount()), s.numCell);

        rowNum++; // spacer
        XSSFRow totalExpense = sh.createRow(rowNum++);
        setText(totalExpense, 0, "Total Expense", s.groupBold);
        setBigDecimal(totalExpense, 1, nz(eng.totalExpense()), s.groupBoldNum);

        XSSFRow totalIncome = sh.createRow(rowNum++);
        setText(totalIncome, 0, "Total Income", s.groupBold);
        setBigDecimal(totalIncome, 1, nz(eng.totalIncome()), s.groupBoldNum);

        XSSFRow contrib = sh.createRow(rowNum++);
        setText(contrib, 0, "Contribution", s.groupBold);
        setBigDecimal(contrib, 1, nz(eng.contribution()), s.groupBoldNum);

        XSSFRow contribPct = sh.createRow(rowNum++);
        setText(contribPct, 0, "Contribution %", s.groupBold);
        setBigDecimal(contribPct, 1, nz(eng.contributionPct()), s.groupBoldPct);

        setColumnWidths(sh, new int[] {8000, 5200, 2800, 4000});
    }

    private int writeSectionTotalRow(XSSFSheet sh, int rowNum, String label, BigDecimal amount, Styles s) {
        XSSFRow row = sh.createRow(rowNum++);
        setText(row, 0, label, s.plain);
        setBigDecimal(row, 1, nz(amount), s.numCell);
        return rowNum;
    }

    // ── Sheet: Per-supervisor "Anbazhagan-TS"-style costing report ─────────────────────────

    private void writeSupervisorSheet(
        XSSFWorkbook wb, String sheetName, DbsSupervisorDayResponse sup, LocalDate date, Styles s) {

        XSSFSheet sh = wb.createSheet(sheetName);
        int rowNum = 0;

        XSSFRow title = sh.createRow(rowNum++);
        setText(title, 0, "Costing Report", s.title);
        sh.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

        XSSFRow meta = sh.createRow(rowNum++);
        setText(meta, 0, "Supervisor :", s.bold);
        setText(meta, 1, shortUuid(sup.supervisorUserId()), s.plain);
        setText(meta, 3, "Date :", s.bold);
        setText(meta, 4, date.toString(), s.plain);

        XSSFRow projMeta = sh.createRow(rowNum++);
        setText(projMeta, 0, "Project :", s.bold);
        setText(projMeta, 1, sup.projectId() == null ? "" : sup.projectId().toString(), s.plain);

        rowNum++; // spacer

        // Six section blocks — Description / Unit / Rate / For-the-day / Quantity / Total Amount
        rowNum = writeSupervisorSection(sh, rowNum, "E. Material", sup.materialLines(), sup.materialAmount(), s);
        rowNum = writeSupervisorSection(sh, rowNum, "A. Man Power", sup.manpowerLines(), sup.manpowerAmount(), s);
        rowNum = writeSupervisorSection(sh, rowNum, "B. Catering / Admin", sup.adminLines(), sup.adminAmount(), s);
        rowNum = writeSupervisorSection(sh, rowNum, "C. Machinery", sup.machineryLines(), sup.machineryAmount(), s);
        rowNum = writeSupervisorSection(sh, rowNum, "D. Fuel", sup.fuelLines(), sup.fuelAmount(), s);
        rowNum = writeSupervisorSection(sh, rowNum, "F. SubContractor", sup.subcontractLines(), sup.subcontractAmount(), s);

        // BOQ Work block
        rowNum++;
        XSSFRow boqHeader = sh.createRow(rowNum++);
        setText(boqHeader, 0, "BOQ Work", s.sectionHeader);
        for (int c = 1; c <= 8; c++) {
            XSSFCell cell = boqHeader.createCell(c);
            cell.setCellStyle(s.sectionHeader);
        }
        sh.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, 8));

        XSSFRow boqCols = sh.createRow(rowNum++);
        setText(boqCols, 0, "Description", s.headerCenter);
        setText(boqCols, 1, "Unit", s.headerCenter);
        setText(boqCols, 2, "Rate", s.headerCenter);
        setText(boqCols, 3, "For the Day", s.headerCenter);
        setText(boqCols, 4, "Proj Qty", s.headerCenter);
        setText(boqCols, 5, "Proj Amount", s.headerCenter);
        setText(boqCols, 6, "Ach Qty", s.headerCenter);
        setText(boqCols, 7, "Ach Amount", s.headerCenter);
        setText(boqCols, 8, "% Ach", s.headerCenter);

        for (DbsSectionLineDto line : nz(sup.boqLines())) {
            XSSFRow row = sh.createRow(rowNum++);
            setText(row, 0, line.description(), s.plain);
            setText(row, 1, line.unit(), s.plain);
            setBigDecimal(row, 2, nz(line.rate()), s.numCell);
            // For the day = quantity (executed today)
            setBigDecimal(row, 3, nz(line.quantity()), s.numCell);
            // We don't carry planned-vs-achieved per BOQ line on the supervisor row in v1
            // beyond totals, so the per-line table only shows what we have: rate, qty, total.
            setBigDecimal(row, 7, nz(line.totalAmount()), s.numCell);
        }

        // BOQ totals row
        XSSFRow boqTotals = sh.createRow(rowNum++);
        setText(boqTotals, 0, "BOQ Totals", s.groupBold);
        setBigDecimal(boqTotals, 3, nz(sup.boqForTheDayAmount()), s.groupBoldNum);
        setBigDecimal(boqTotals, 5, nz(sup.boqPlannedAmount()), s.groupBoldNum);
        setBigDecimal(boqTotals, 7, nz(sup.boqAchievedAmount()), s.groupBoldNum);
        setBigDecimal(boqTotals, 8,
            ratio(sup.boqAchievedAmount(), sup.boqPlannedAmount()), s.groupBoldPct);

        rowNum++; // spacer

        // Final P&L totals
        XSSFRow expRow = sh.createRow(rowNum++);
        setText(expRow, 0, "Total Expense (A+B+C+D+E+F)", s.groupBold);
        setBigDecimal(expRow, 5, nz(sup.totalExpense()), s.groupBoldNum);

        XSSFRow incRow = sh.createRow(rowNum++);
        setText(incRow, 0, "Total Income (BOQ Achieved)", s.groupBold);
        setBigDecimal(incRow, 5, nz(sup.totalIncome()), s.groupBoldNum);

        XSSFRow contribRow = sh.createRow(rowNum++);
        setText(contribRow, 0, "Contribution (Income − Expense)", s.groupBold);
        setBigDecimal(contribRow, 5, nz(sup.contribution()), s.groupBoldNum);

        XSSFRow pctRow = sh.createRow(rowNum++);
        setText(pctRow, 0, "Contribution %", s.groupBold);
        setBigDecimal(pctRow, 5, nz(sup.contributionPct()), s.groupBoldPct);

        setColumnWidths(sh, new int[] {
            10000, 2800, 3200, 3200, 3200, 4000, 3200, 4000, 2800
        });
    }

    private int writeSupervisorSection(
        XSSFSheet sh, int rowNum, String sectionLabel,
        List<DbsSectionLineDto> lines, BigDecimal sectionTotal, Styles s) {

        // Section banner
        XSSFRow banner = sh.createRow(rowNum++);
        setText(banner, 0, sectionLabel, s.sectionHeader);
        for (int c = 1; c <= 5; c++) {
            banner.createCell(c).setCellStyle(s.sectionHeader);
        }
        sh.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, 5));

        // Column headers
        XSSFRow head = sh.createRow(rowNum++);
        setText(head, 0, "Description", s.headerCenter);
        setText(head, 1, "Unit", s.headerCenter);
        setText(head, 2, "Rate", s.headerCenter);
        setText(head, 3, "For the Day", s.headerCenter);
        setText(head, 4, "Quantity", s.headerCenter);
        setText(head, 5, "Total Amount", s.headerCenter);

        for (DbsSectionLineDto line : nz(lines)) {
            XSSFRow row = sh.createRow(rowNum++);
            setText(row, 0, line.description(), s.plain);
            setText(row, 1, line.unit(), s.plain);
            setBigDecimal(row, 2, nz(line.rate()), s.numCell);
            setBigDecimal(row, 3, nz(line.quantity()), s.numCell);
            setBigDecimal(row, 4, nz(line.quantity()), s.numCell);
            setBigDecimal(row, 5, nz(line.totalAmount()), s.numCell);
        }

        XSSFRow totalsRow = sh.createRow(rowNum++);
        setText(totalsRow, 0, sectionLabel + " — Total", s.groupBold);
        setBigDecimal(totalsRow, 5, nz(sectionTotal), s.groupBoldNum);

        rowNum++; // spacer between sections
        return rowNum;
    }

    // ── Sheet: "MP & Eqpt Summary" (Equipment Register pivot by CM × shift) ────────────────

    /**
     * Equipment Register pivot. One row per equipment type. Columns: SL No, Equipment,
     * Total, and a (Day, Night) pair for every CM present in the register, finishing
     * with an "Off Road" column carrying the unattached (cm_user_id IS NULL) bucket.
     */
    private void writeEquipmentRegisterSheet(XSSFWorkbook wb, EquipmentRegisterResponse data, Styles s) {
        XSSFSheet sh = wb.createSheet(safeSheetName("MP & Eqpt Summary", wb));
        int rowNum = 0;

        XSSFRow title = sh.createRow(rowNum++);
        setText(title, 0, "Equipment Deployment Register — MP & Eqpt Summary", s.title);

        XSSFRow meta = sh.createRow(rowNum++);
        setText(meta, 0, "Date :", s.bold);
        setText(meta, 1, data.date() == null ? "" : data.date().toString(), s.plain);
        rowNum++; // spacer

        // Distinct CMs across all equipment rows — preserves insertion order so the
        // column layout is stable across calls with the same data.
        LinkedHashMap<UUID, String> cms = collectCms(equipmentCms(data));

        // Header — SL No | Equipment | Total | <Cm1 Day> <Cm1 Night> ... | Off Road
        XSSFRow header = sh.createRow(rowNum++);
        setText(header, 0, "SL No", s.headerCenter);
        setText(header, 1, "Equipment", s.headerCenter);
        setText(header, 2, "Total", s.headerCenter);
        int col = 3;
        for (Map.Entry<UUID, String> cm : cms.entrySet()) {
            setText(header, col++, cm.getValue() + " Day", s.headerCenter);
            setText(header, col++, cm.getValue() + " Night", s.headerCenter);
        }
        int offRoadCol = col;
        setText(header, offRoadCol, "Off Road", s.headerCenter);

        // Rows
        int sl = 1;
        for (EquipmentRegisterTypeRow type : nz(data.equipment())) {
            XSSFRow row = sh.createRow(rowNum++);
            setText(row, 0, String.valueOf(sl++), s.plain);
            setText(row, 1, type.type(), s.plain);
            setBigDecimal(row, 2, java.math.BigDecimal.valueOf(type.total()), s.numCell);

            int c = 3;
            int offRoad = 0;
            for (Map.Entry<UUID, String> cmEntry : cms.entrySet()) {
                CmShiftCount match = findCmEntry(type.byCm(), cmEntry.getKey());
                setBigDecimal(row, c++,
                    java.math.BigDecimal.valueOf(match == null ? 0 : match.day()), s.numCell);
                setBigDecimal(row, c++,
                    java.math.BigDecimal.valueOf(match == null ? 0 : match.night()), s.numCell);
            }
            // Unattached bucket: any byCm entry with null cmUserId — sum day + night.
            for (CmShiftCount e : nz(type.byCm())) {
                if (e.cmUserId() == null) offRoad += e.total();
            }
            setBigDecimal(row, offRoadCol, java.math.BigDecimal.valueOf(offRoad), s.numCell);
        }

        // Make the first columns wide enough for labels; per-CM columns get a uniform
        // narrow width.
        int[] widths = new int[offRoadCol + 1];
        widths[0] = 2400;
        widths[1] = 7200;
        widths[2] = 3200;
        for (int i = 3; i < offRoadCol; i++) widths[i] = 3200;
        widths[offRoadCol] = 3200;
        setColumnWidths(sh, widths);
    }

    // ── Sheet: "Plant Summary" ─────────────────────────────────────────────────────────────

    /**
     * Plant Summary. One row per equipment type. Columns: Equipment, Variation,
     * Available, Total as per Site, and a (No's/Day, Total) pair per CM.
     *
     * <p>{@code Variation} and {@code Available} are blank in v1 — the plan defers the
     * plant-census master table.
     */
    private void writePlantSummarySheet(XSSFWorkbook wb, EquipmentRegisterResponse data, Styles s) {
        XSSFSheet sh = wb.createSheet(safeSheetName("Plant Summary", wb));
        int rowNum = 0;

        XSSFRow title = sh.createRow(rowNum++);
        setText(title, 0, "Plant Summary", s.title);

        XSSFRow meta = sh.createRow(rowNum++);
        setText(meta, 0, "Date :", s.bold);
        setText(meta, 1, data.date() == null ? "" : data.date().toString(), s.plain);
        rowNum++; // spacer

        LinkedHashMap<UUID, String> cms = collectCms(equipmentCms(data));

        XSSFRow header = sh.createRow(rowNum++);
        setText(header, 0, "Equipment", s.headerCenter);
        setText(header, 1, "Variation", s.headerCenter);
        setText(header, 2, "Available", s.headerCenter);
        setText(header, 3, "Total as per Site", s.headerCenter);
        int col = 4;
        for (Map.Entry<UUID, String> cm : cms.entrySet()) {
            setText(header, col++, cm.getValue() + " No's/Day", s.headerCenter);
            setText(header, col++, cm.getValue() + " Total", s.headerCenter);
        }

        for (EquipmentRegisterTypeRow type : nz(data.equipment())) {
            XSSFRow row = sh.createRow(rowNum++);
            setText(row, 0, type.type(), s.plain);
            // Variation / Available intentionally blank — plant census deferred.
            setText(row, 1, "", s.plain);
            setText(row, 2, "", s.plain);
            setBigDecimal(row, 3, java.math.BigDecimal.valueOf(type.total()), s.numCell);
            int c = 4;
            for (Map.Entry<UUID, String> cmEntry : cms.entrySet()) {
                CmShiftCount match = findCmEntry(type.byCm(), cmEntry.getKey());
                setBigDecimal(row, c++, java.math.BigDecimal.valueOf(match == null ? 0 : match.day()),
                    s.numCell);
                setBigDecimal(row, c++, java.math.BigDecimal.valueOf(match == null ? 0 : match.total()),
                    s.numCell);
            }
        }

        int columns = 4 + (cms.size() * 2);
        int[] widths = new int[Math.max(columns, 4)];
        widths[0] = 7200;
        widths[1] = 3200;
        widths[2] = 3200;
        widths[3] = 4400;
        for (int i = 4; i < widths.length; i++) widths[i] = 3200;
        setColumnWidths(sh, widths);
    }

    // ── Sheet: "Eqpmnt & MP Days" (Cumulative Days) ────────────────────────────────────────

    /**
     * Cumulative deployment-days. Two stacked tables — Equipment first, then Manpower
     * — separated by a blank row. Header is {@code Resource | Cumulative Days} for each.
     */
    private void writeCumulativeDaysSheet(XSSFWorkbook wb, CumulativeDaysResponse data, Styles s) {
        XSSFSheet sh = wb.createSheet(safeSheetName("Eqpmnt & MP Days", wb));
        int rowNum = 0;

        XSSFRow title = sh.createRow(rowNum++);
        setText(title, 0, "Cumulative Equipment & Manpower Days", s.title);

        XSSFRow meta = sh.createRow(rowNum++);
        setText(meta, 0, "As of :", s.bold);
        setText(meta, 1, data.asOfDate() == null ? "" : data.asOfDate().toString(), s.plain);
        rowNum++; // spacer

        // Equipment block
        XSSFRow eqBanner = sh.createRow(rowNum++);
        setText(eqBanner, 0, "Equipment", s.sectionHeader);
        eqBanner.createCell(1).setCellStyle(s.sectionHeader);
        sh.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, 1));

        XSSFRow eqHeader = sh.createRow(rowNum++);
        setText(eqHeader, 0, "Resource", s.headerCenter);
        setText(eqHeader, 1, "Cumulative Days", s.headerCenter);
        for (CumulativeDaysResponse.CumulativeEquipmentDays eq : nz(data.equipment())) {
            XSSFRow row = sh.createRow(rowNum++);
            setText(row, 0, eq.type(), s.plain);
            setBigDecimal(row, 1, java.math.BigDecimal.valueOf(eq.days()), s.numCell);
        }

        rowNum++; // blank separator row

        // Manpower block
        XSSFRow mpBanner = sh.createRow(rowNum++);
        setText(mpBanner, 0, "Manpower", s.sectionHeader);
        mpBanner.createCell(1).setCellStyle(s.sectionHeader);
        sh.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, 1));

        XSSFRow mpHeader = sh.createRow(rowNum++);
        setText(mpHeader, 0, "Resource", s.headerCenter);
        setText(mpHeader, 1, "Cumulative Days", s.headerCenter);
        for (CumulativeDaysResponse.CumulativeManpowerDays mp : nz(data.manpower())) {
            XSSFRow row = sh.createRow(rowNum++);
            setText(row, 0, mp.trade(), s.plain);
            setBigDecimal(row, 1, java.math.BigDecimal.valueOf(mp.days()), s.numCell);
        }

        setColumnWidths(sh, new int[] {8000, 4400});
    }

    // ── Register helpers ───────────────────────────────────────────────────────────────────

    /**
     * Walks the equipment register response and returns the distinct (cmUserId → name)
     * map, omitting the unattached bucket — that one lives in the Off Road column.
     */
    private static List<CmShiftCount> equipmentCms(EquipmentRegisterResponse data) {
        List<CmShiftCount> out = new ArrayList<>();
        for (EquipmentRegisterTypeRow t : nz(data.equipment())) {
            for (CmShiftCount c : nz(t.byCm())) {
                if (c.cmUserId() != null) out.add(c);
            }
        }
        return out;
    }

    /**
     * Collapse a list of (potentially repeated) CM entries into a stable
     * cmUserId → cmName map. LinkedHashMap preserves first-seen order so column
     * layout is deterministic between calls.
     */
    private static LinkedHashMap<UUID, String> collectCms(List<CmShiftCount> entries) {
        LinkedHashMap<UUID, String> cms = new LinkedHashMap<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (CmShiftCount c : entries) {
            if (c.cmUserId() == null) continue;
            if (seen.add(c.cmUserId())) {
                cms.put(c.cmUserId(), c.cmName() == null ? shortUuid(c.cmUserId()) : c.cmName());
            }
        }
        return cms;
    }

    private static CmShiftCount findCmEntry(List<CmShiftCount> byCm, UUID cmUserId) {
        if (byCm == null) return null;
        for (CmShiftCount c : byCm) {
            if (cmUserId.equals(c.cmUserId())) return c;
        }
        return null;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal n = nz(numerator);
        BigDecimal d = nz(denominator);
        if (d.signum() == 0) return BigDecimal.ZERO;
        return n.divide(d, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return Optional.ofNullable(v).orElse(BigDecimal.ZERO);
    }

    private static <T> List<T> nz(List<T> v) {
        return v == null ? List.of() : v;
    }

    private static String shortUuid(UUID id) {
        if (id == null) return "(unassigned)";
        String s = id.toString();
        return s.length() >= 8 ? s.substring(0, 8) : s;
    }

    /**
     * Excel sheet name rules: ≤31 chars, no [ ] : * ? / \ characters, must be unique. POI
     * provides {@link WorkbookUtil#createSafeSheetName(String)} for the char rules; we
     * append a counter to disambiguate collisions.
     */
    private static String safeSheetName(String raw, XSSFWorkbook wb) {
        String base = WorkbookUtil.createSafeSheetName(raw == null || raw.isBlank() ? "Sheet" : raw);
        if (base.length() > 25) base = base.substring(0, 25);
        String candidate = base;
        int n = 1;
        while (wb.getSheet(candidate) != null) {
            String suffix = "-" + (++n);
            int max = 31 - suffix.length();
            String trimmed = base.length() > max ? base.substring(0, max) : base;
            candidate = trimmed + suffix;
        }
        return candidate;
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

    /** Per-workbook style cache so we stay under Excel's 64 k-style ceiling on big books. */
    private static final class Styles {
        final CellStyle plain;
        final CellStyle bold;
        final CellStyle title;
        final CellStyle headerCenter;
        final CellStyle sectionHeader;
        final CellStyle groupBold;
        final CellStyle groupBoldNum;
        final CellStyle groupBoldPct;
        final CellStyle numCell;
        final CellStyle pctCell;

        Styles(XSSFWorkbook wb) {
            DataFormat df = wb.createDataFormat();

            Font fontPlain = wb.createFont();
            Font fontBold = wb.createFont();
            fontBold.setBold(true);
            Font fontTitle = wb.createFont();
            fontTitle.setBold(true);
            fontTitle.setFontHeightInPoints((short) 13);

            plain = wb.createCellStyle();
            plain.setFont(fontPlain);
            borderAll(plain);

            bold = wb.createCellStyle();
            bold.setFont(fontBold);

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

            sectionHeader = wb.createCellStyle();
            sectionHeader.setFont(fontBold);
            sectionHeader.setAlignment(HorizontalAlignment.LEFT);
            sectionHeader.setVerticalAlignment(VerticalAlignment.CENTER);
            sectionHeader.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            sectionHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            borderAll(sectionHeader);

            groupBold = wb.createCellStyle();
            groupBold.setFont(fontBold);
            groupBold.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
            groupBold.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            borderAll(groupBold);

            groupBoldNum = wb.createCellStyle();
            groupBoldNum.cloneStyleFrom(groupBold);
            groupBoldNum.setDataFormat(df.getFormat("#,##0.00"));
            groupBoldNum.setAlignment(HorizontalAlignment.RIGHT);

            groupBoldPct = wb.createCellStyle();
            groupBoldPct.cloneStyleFrom(groupBold);
            groupBoldPct.setDataFormat(df.getFormat("0.00%"));
            groupBoldPct.setAlignment(HorizontalAlignment.RIGHT);

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
        }

        private static void borderAll(CellStyle s) {
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
        }
    }
}
