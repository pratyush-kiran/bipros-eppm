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
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Renders the PM- and supervisor-level DBS reports to a PDF byte array via
 * openhtmltopdf. We assemble a strict XHTML document with minimal inline CSS — the
 * openhtmltopdf renderer is strict about XHTML so every attribute is quoted, every tag
 * closed, and reserved characters are escaped.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DbsPdfWriter {

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final DecimalFormat PCT = new DecimalFormat("0.00%");

    private final DbsQueryService queryService;
    private final RegisterAggregationService registerService;

    public byte[] writePmReport(UUID projectId, LocalDate date) {
        DbsProjectDayResponse project = queryService.getProjectDay(projectId, date);
        List<DbsEngineerDayResponse> engineers = new ArrayList<>();
        for (UUID eid : nz(project.engineerIds())) {
            engineers.add(queryService.getEngineerDay(projectId, eid, date));
        }
        List<DbsSupervisorSummaryDto> supervisors = queryService.listSupervisorsForDay(projectId, date);
        EquipmentRegisterResponse equipmentRegister =
            registerService.getEquipmentRegister(projectId, date, null);
        ManpowerRegisterResponse manpowerRegister =
            registerService.getManpowerRegister(projectId, date, null);
        CumulativeDaysResponse cumulative = registerService.cumulative(projectId, date, null);

        String html = renderPmHtml(projectId, date, project, engineers, supervisors,
            equipmentRegister, manpowerRegister, cumulative);
        return renderToPdf(html, "PM report " + projectId + " " + date);
    }

    public byte[] writeSupervisorReport(UUID projectId, UUID supervisorUserId, LocalDate date) {
        DbsSupervisorDayResponse sup = queryService.getSupervisorDay(projectId, supervisorUserId, date);
        String html = renderSupervisorHtml(projectId, supervisorUserId, date, sup);
        return renderToPdf(html, "Supervisor report " + projectId + "/" + supervisorUserId + " " + date);
    }

    // ── HTML assembly ──────────────────────────────────────────────────────────────────────

    private String renderPmHtml(
        UUID projectId, LocalDate date,
        DbsProjectDayResponse project,
        List<DbsEngineerDayResponse> engineers,
        List<DbsSupervisorSummaryDto> supervisors,
        EquipmentRegisterResponse equipmentRegister,
        ManpowerRegisterResponse manpowerRegister,
        CumulativeDaysResponse cumulative) {

        StringBuilder sb = new StringBuilder(8 * 1024);
        sb.append(htmlHead("DBS — Summary-Financial"));
        sb.append("<h1>Daily Balance Sheet — Summary-Financial (PM)</h1>");
        sb.append("<p class=\"meta\"><strong>Project:</strong> ").append(esc(projectId.toString()))
            .append(" &nbsp; <strong>Date:</strong> ").append(esc(date.toString())).append("</p>");

        sb.append("<h2>Per-Engineer Breakdown</h2>");
        sb.append("<table><thead><tr>")
            .append("<th>Engineer</th>")
            .append("<th class=\"r\">Plan Amount</th>")
            .append("<th class=\"r\">Achieved (Income)</th>")
            .append("<th class=\"r\">Cost (Expense)</th>")
            .append("<th class=\"r\">Cost %</th>")
            .append("<th class=\"r\">Contribution</th>")
            .append("<th class=\"r\">Contribution %</th>")
            .append("<th>P/L</th>")
            .append("</tr></thead><tbody>");
        for (DbsEngineerDayResponse eng : engineers) {
            BigDecimal pl = nz(eng.contribution());
            sb.append("<tr>")
                .append(td(shortUuid(eng.engineerUserId())))
                .append(tdR(money(eng.boqPlannedAmount())))
                .append(tdR(money(eng.totalIncome())))
                .append(tdR(money(eng.totalExpense())))
                .append(tdR(pct(ratio(eng.totalExpense(), eng.totalIncome()))))
                .append(tdR(money(eng.contribution())))
                .append(tdR(pct(eng.contributionPct())))
                .append(td(pl.signum() > 0 ? "Profit" : pl.signum() < 0 ? "Loss" : "Flat"))
                .append("</tr>");
        }
        sb.append("<tr class=\"totals\">")
            .append(td("Project Totals"))
            .append(tdR(money(project.boqPlannedAmount())))
            .append(tdR(money(project.totalIncome())))
            .append(tdR(money(project.totalExpense())))
            .append(tdR(pct(ratio(project.totalExpense(), project.totalIncome()))))
            .append(tdR(money(project.contribution())))
            .append(tdR(pct(project.contributionPct())))
            .append(td(nz(project.contribution()).signum() > 0 ? "Profit"
                : nz(project.contribution()).signum() < 0 ? "Loss" : "Flat"))
            .append("</tr>");
        sb.append("</tbody></table>");

        if (project.cumulativeExpense() != null
            || project.cumulativeIncome() != null
            || project.cumulativeContribution() != null) {
            sb.append("<h3>Cumulative to Date</h3>")
                .append("<table><tbody>")
                .append("<tr>").append(td("Cumulative Expense")).append(tdR(money(project.cumulativeExpense()))).append("</tr>")
                .append("<tr>").append(td("Cumulative Income")).append(tdR(money(project.cumulativeIncome()))).append("</tr>")
                .append("<tr>").append(td("Cumulative Contribution")).append(tdR(money(project.cumulativeContribution()))).append("</tr>")
                .append("</tbody></table>");
        }

        if (!supervisors.isEmpty()) {
            sb.append("<h2>Supervisor Roster</h2>");
            sb.append("<table><thead><tr>")
                .append("<th>Supervisor</th>")
                .append("<th class=\"r\">Expense</th>")
                .append("<th class=\"r\">Income</th>")
                .append("<th class=\"r\">Contribution</th>")
                .append("<th class=\"r\">Contribution %</th>")
                .append("</tr></thead><tbody>");
            for (DbsSupervisorSummaryDto sup : supervisors) {
                sb.append("<tr>")
                    .append(td(sup.supervisorName() != null && !sup.supervisorName().isBlank()
                        ? sup.supervisorName()
                        : shortUuid(sup.supervisorUserId())))
                    .append(tdR(money(sup.totalExpense())))
                    .append(tdR(money(sup.totalIncome())))
                    .append(tdR(money(sup.contribution())))
                    .append(tdR(pct(sup.contributionPct())))
                    .append("</tr>");
            }
            sb.append("</tbody></table>");
        }

        // Phase 9 — Equipment Register, Manpower Register, Cumulative Days.
        // Mirrors the new Excel sheets ("MP & Eqpt Summary", "Plant Summary",
        // "Eqpmnt & MP Days") so the PDF stays in lockstep with the workbook.
        appendEquipmentRegisterSection(sb, equipmentRegister);
        appendManpowerRegisterSection(sb, manpowerRegister);
        appendCumulativeDaysSection(sb, cumulative);

        sb.append(htmlFoot());
        return sb.toString();
    }

    private void appendEquipmentRegisterSection(StringBuilder sb, EquipmentRegisterResponse data) {
        if (data == null) return;
        sb.append("<h2>Equipment Register</h2>");
        if (data.equipment() == null || data.equipment().isEmpty()) {
            sb.append("<p class=\"muted\">No equipment deployed.</p>");
            return;
        }
        LinkedHashMap<UUID, String> cms = collectEquipmentCms(data);
        sb.append("<table><thead><tr>")
            .append("<th>Equipment</th>")
            .append("<th class=\"r\">Total</th>");
        for (Map.Entry<UUID, String> e : cms.entrySet()) {
            sb.append("<th class=\"r\">").append(esc(e.getValue())).append(" Day</th>");
            sb.append("<th class=\"r\">").append(esc(e.getValue())).append(" Night</th>");
        }
        sb.append("<th class=\"r\">Off Road</th>")
            .append("</tr></thead><tbody>");
        for (EquipmentRegisterTypeRow type : data.equipment()) {
            sb.append("<tr>")
                .append(td(type.type()))
                .append(tdR(String.valueOf(type.total())));
            for (Map.Entry<UUID, String> e : cms.entrySet()) {
                CmShiftCount match = findCmEntry(type.byCm(), e.getKey());
                sb.append(tdR(String.valueOf(match == null ? 0 : match.day())));
                sb.append(tdR(String.valueOf(match == null ? 0 : match.night())));
            }
            int offRoad = 0;
            if (type.byCm() != null) {
                for (CmShiftCount c : type.byCm()) {
                    if (c.cmUserId() == null) offRoad += c.total();
                }
            }
            sb.append(tdR(String.valueOf(offRoad)))
                .append("</tr>");
        }
        sb.append("</tbody></table>");
    }

    private void appendManpowerRegisterSection(StringBuilder sb, ManpowerRegisterResponse data) {
        if (data == null) return;
        sb.append("<h2>Manpower Register</h2>");
        if (data.manpower() == null || data.manpower().isEmpty()) {
            sb.append("<p class=\"muted\">No manpower deployed.</p>");
            return;
        }
        LinkedHashMap<UUID, String> cms = collectManpowerCms(data);
        sb.append("<table><thead><tr>")
            .append("<th>Trade</th>")
            .append("<th class=\"r\">Total</th>");
        for (Map.Entry<UUID, String> e : cms.entrySet()) {
            sb.append("<th class=\"r\">").append(esc(e.getValue())).append(" Day</th>");
            sb.append("<th class=\"r\">").append(esc(e.getValue())).append(" Night</th>");
        }
        sb.append("<th class=\"r\">Off Roster</th>")
            .append("</tr></thead><tbody>");
        for (ManpowerRegisterTradeRow trade : data.manpower()) {
            sb.append("<tr>")
                .append(td(trade.trade()))
                .append(tdR(String.valueOf(trade.total())));
            for (Map.Entry<UUID, String> e : cms.entrySet()) {
                CmShiftCount match = findCmEntry(trade.byCm(), e.getKey());
                sb.append(tdR(String.valueOf(match == null ? 0 : match.day())));
                sb.append(tdR(String.valueOf(match == null ? 0 : match.night())));
            }
            int offRoster = 0;
            if (trade.byCm() != null) {
                for (CmShiftCount c : trade.byCm()) {
                    if (c.cmUserId() == null) offRoster += c.total();
                }
            }
            sb.append(tdR(String.valueOf(offRoster)))
                .append("</tr>");
        }
        sb.append("</tbody></table>");
    }

    private void appendCumulativeDaysSection(StringBuilder sb, CumulativeDaysResponse data) {
        if (data == null) return;
        sb.append("<h2>Cumulative Days</h2>");
        sb.append("<h3>Equipment</h3>");
        sb.append("<table><thead><tr>")
            .append("<th>Resource</th>")
            .append("<th class=\"r\">Cumulative Days</th>")
            .append("</tr></thead><tbody>");
        if (data.equipment() == null || data.equipment().isEmpty()) {
            sb.append("<tr><td colspan=\"2\" class=\"muted\">No data</td></tr>");
        } else {
            for (CumulativeDaysResponse.CumulativeEquipmentDays eq : data.equipment()) {
                sb.append("<tr>")
                    .append(td(eq.type()))
                    .append(tdR(String.valueOf(eq.days())))
                    .append("</tr>");
            }
        }
        sb.append("</tbody></table>");

        sb.append("<h3>Manpower</h3>");
        sb.append("<table><thead><tr>")
            .append("<th>Resource</th>")
            .append("<th class=\"r\">Cumulative Days</th>")
            .append("</tr></thead><tbody>");
        if (data.manpower() == null || data.manpower().isEmpty()) {
            sb.append("<tr><td colspan=\"2\" class=\"muted\">No data</td></tr>");
        } else {
            for (CumulativeDaysResponse.CumulativeManpowerDays mp : data.manpower()) {
                sb.append("<tr>")
                    .append(td(mp.trade()))
                    .append(tdR(String.valueOf(mp.days())))
                    .append("</tr>");
            }
        }
        sb.append("</tbody></table>");
    }

    private static LinkedHashMap<UUID, String> collectEquipmentCms(EquipmentRegisterResponse data) {
        LinkedHashMap<UUID, String> cms = new LinkedHashMap<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (EquipmentRegisterTypeRow t : data.equipment()) {
            if (t.byCm() == null) continue;
            for (CmShiftCount c : t.byCm()) {
                if (c.cmUserId() == null) continue;
                if (seen.add(c.cmUserId())) {
                    cms.put(c.cmUserId(), c.cmName() == null ? shortUuid(c.cmUserId()) : c.cmName());
                }
            }
        }
        return cms;
    }

    private static LinkedHashMap<UUID, String> collectManpowerCms(ManpowerRegisterResponse data) {
        LinkedHashMap<UUID, String> cms = new LinkedHashMap<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (ManpowerRegisterTradeRow t : data.manpower()) {
            if (t.byCm() == null) continue;
            for (CmShiftCount c : t.byCm()) {
                if (c.cmUserId() == null) continue;
                if (seen.add(c.cmUserId())) {
                    cms.put(c.cmUserId(), c.cmName() == null ? shortUuid(c.cmUserId()) : c.cmName());
                }
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

    private String renderSupervisorHtml(
        UUID projectId, UUID supervisorUserId, LocalDate date, DbsSupervisorDayResponse sup) {

        StringBuilder sb = new StringBuilder(8 * 1024);
        sb.append(htmlHead("DBS — Supervisor Costing Report"));
        sb.append("<h1>Supervisor Daily Costing Report</h1>");
        sb.append("<p class=\"meta\">")
            .append("<strong>Supervisor:</strong> ").append(esc(shortUuid(supervisorUserId)))
            .append(" &nbsp; <strong>Project:</strong> ").append(esc(projectId.toString()))
            .append(" &nbsp; <strong>Date:</strong> ").append(esc(date.toString()))
            .append("</p>");

        sectionTable(sb, "E. Material", sup.materialLines(), sup.materialAmount());
        sectionTable(sb, "A. Man Power", sup.manpowerLines(), sup.manpowerAmount());
        sectionTable(sb, "B. Catering / Admin", sup.adminLines(), sup.adminAmount());
        sectionTable(sb, "C. Machinery", sup.machineryLines(), sup.machineryAmount());
        sectionTable(sb, "D. Fuel", sup.fuelLines(), sup.fuelAmount());
        sectionTable(sb, "F. SubContractor", sup.subcontractLines(), sup.subcontractAmount());
        sectionTable(sb, "BOQ Work", sup.boqLines(), sup.boqAchievedAmount());

        sb.append("<h2>Daily P&amp;L</h2>");
        sb.append("<table><tbody>")
            .append("<tr>").append(td("Total Expense (A+B+C+D+E+F)")).append(tdR(money(sup.totalExpense()))).append("</tr>")
            .append("<tr>").append(td("Total Income (BOQ Achieved)")).append(tdR(money(sup.totalIncome()))).append("</tr>")
            .append("<tr class=\"totals\">").append(td("Contribution")).append(tdR(money(sup.contribution()))).append("</tr>")
            .append("<tr class=\"totals\">").append(td("Contribution %")).append(tdR(pct(sup.contributionPct()))).append("</tr>")
            .append("</tbody></table>");

        sb.append(htmlFoot());
        return sb.toString();
    }

    private void sectionTable(StringBuilder sb, String title, List<DbsSectionLineDto> lines, BigDecimal total) {
        sb.append("<h2>").append(esc(title)).append("</h2>");
        sb.append("<table><thead><tr>")
            .append("<th>Description</th>")
            .append("<th>Unit</th>")
            .append("<th class=\"r\">Rate</th>")
            .append("<th class=\"r\">Quantity</th>")
            .append("<th class=\"r\">Total Amount</th>")
            .append("</tr></thead><tbody>");
        if (lines == null || lines.isEmpty()) {
            sb.append("<tr><td colspan=\"5\" class=\"muted\">No data</td></tr>");
        } else {
            for (DbsSectionLineDto line : lines) {
                sb.append("<tr>")
                    .append(td(line.description()))
                    .append(td(line.unit()))
                    .append(tdR(money(line.rate())))
                    .append(tdR(money(line.quantity())))
                    .append(tdR(money(line.totalAmount())))
                    .append("</tr>");
            }
        }
        sb.append("<tr class=\"totals\">")
            .append("<td colspan=\"4\">Section Total</td>")
            .append(tdR(money(total)))
            .append("</tr>");
        sb.append("</tbody></table>");
    }

    // ── PDF render ─────────────────────────────────────────────────────────────────────────

    private byte[] renderToPdf(String html, String label) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            b.withHtmlContent(html, null);
            b.toStream(baos);
            b.run();
            return baos.toByteArray();
        } catch (Exception ex) {
            log.error("Failed to render DBS PDF ({})", label, ex);
            throw new RuntimeException("Failed to generate DBS PDF", ex);
        }
    }

    // ── HTML helpers ───────────────────────────────────────────────────────────────────────

    private static String htmlHead(String title) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" "
            + "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>"
            + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />"
            + "<title>" + esc(title) + "</title>"
            + "<style type=\"text/css\">"
            + "body { font-family: sans-serif; font-size: 10pt; color: #222; }"
            + "h1 { font-size: 14pt; margin: 0 0 6pt 0; }"
            + "h2 { font-size: 11pt; margin: 12pt 0 4pt 0; padding: 2pt 4pt; "
            + "     background: #e8eef6; border-left: 3pt solid #345; }"
            + "h3 { font-size: 10.5pt; margin: 8pt 0 4pt 0; }"
            + ".meta { margin: 0 0 8pt 0; color: #555; }"
            + "table { width: 100%; border-collapse: collapse; margin: 0 0 6pt 0; }"
            + "th, td { border: 1pt solid #aaa; padding: 2pt 4pt; vertical-align: top; }"
            + "th { background: #ddd; text-align: left; font-weight: bold; }"
            + "td.r, th.r { text-align: right; }"
            + ".muted { color: #888; text-align: center; }"
            + ".totals td { background: #fff8c4; font-weight: bold; }"
            + "</style></head><body>";
    }

    private static String htmlFoot() {
        return "</body></html>";
    }

    private static String td(String value) {
        return "<td>" + esc(value) + "</td>";
    }

    private static String tdR(String value) {
        return "<td class=\"r\">" + esc(value) + "</td>";
    }

    private static String money(BigDecimal v) {
        return MONEY.format(nz(v));
    }

    private static String pct(BigDecimal fraction) {
        return PCT.format(nz(fraction));
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal n = nz(numerator);
        BigDecimal d = nz(denominator);
        if (d.signum() == 0) return BigDecimal.ZERO;
        return n.divide(d, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return Optional.ofNullable(v).orElse(BigDecimal.ZERO);
    }

    private static List<UUID> nz(List<UUID> v) {
        return v == null ? List.of() : v;
    }

    private static String shortUuid(UUID id) {
        if (id == null) return "(unassigned)";
        String s = id.toString();
        return s.length() >= 8 ? s.substring(0, 8) : s;
    }

    /** XHTML-safe escaper. openhtmltopdf is strict; one stray &amp;/&lt;/&gt; breaks the render. */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
