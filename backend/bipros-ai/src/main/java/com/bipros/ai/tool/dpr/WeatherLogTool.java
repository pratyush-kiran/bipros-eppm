package com.bipros.ai.tool.dpr;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DailyWeather;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DailyWeatherRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Weather lookup over Supervisor Daily Reports — Section C. The canonical entity is
 * {@link DailyWeather} (one row per project+date). Operations:
 * <ul>
 *   <li>{@code by_date} — date→weather/temperature window for the project</li>
 *   <li>{@code by_project} — overall weather-condition distribution</li>
 *   <li>{@code link_to_dpr} — pair each weather row with the count of DPRs filed
 *       on that date and a sample weather string from the DPR rows themselves</li>
 * </ul>
 *
 * <p>Falls back to {@link DailyProgressReport#getWeatherCondition()} when a date has
 * no DailyWeather row but DPRs exist for it (so we still report what supervisors saw).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherLogTool implements Tool {

    private final DailyWeatherRepository weatherRepository;
    private final DailyProgressReportRepository dprRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "weather_log";
    }

    @Override
    public String description() {
        return "Use this when the user asks about site weather — \"what was the weather on April 15?\", "
                + "\"how many rainy days last month?\", \"weather distribution this quarter\", \"weather "
                + "and DPRs side by side\". Operations via `op`: `by_date` (per-day weather rows in the "
                + "window — temp, rainfall, wind, condition), `by_project` (counts grouped by weather "
                + "condition string across the window), `link_to_dpr` (for each date, weather + DPR "
                + "count + supervisor-reported weather strings). Inputs: `date_from`, `date_to` "
                + "(default last 30 days). Project-scoped.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        ArrayNode opEnum = objectMapper.createArrayNode();
        opEnum.add("by_date");
        opEnum.add("by_project");
        opEnum.add("link_to_dpr");
        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "string");
        op.set("enum", opEnum);
        op.put("default", "by_date");
        op.put("description", "Operation to run.");
        props.set("op", op);
        props.set("date_from", objectMapper.createObjectNode().put("type", "string").put("format", "date")
                .put("description", "ISO date. Default 30 days before date_to."));
        props.set("date_to", objectMapper.createObjectNode().put("type", "string").put("format", "date")
                .put("description", "ISO date. Default today."));
        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("weather_log needs a project in scope.");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }
        LocalDate dateTo = parseDate(input.path("date_to").asText(null), LocalDate.now());
        LocalDate dateFrom = parseDate(input.path("date_from").asText(null), dateTo.minusDays(30));
        if (dateFrom.isAfter(dateTo)) {
            LocalDate t = dateFrom;
            dateFrom = dateTo;
            dateTo = t;
        }
        String op = orDefault(input.path("op").asText(null), "by_date");
        return switch (op) {
            case "by_project" -> opByProject(projectId, dateFrom, dateTo);
            case "link_to_dpr" -> opLinkToDpr(projectId, dateFrom, dateTo);
            default -> opByDate(projectId, dateFrom, dateTo);
        };
    }

    private ToolResult opByDate(UUID projectId, LocalDate dateFrom, LocalDate dateTo) {
        List<DailyWeather> rows = weatherRepository.findByProjectIdAndLogDateBetweenOrderByLogDateAscIdAsc(
                projectId, dateFrom, dateTo);
        ArrayNode arr = objectMapper.createArrayNode();
        for (DailyWeather w : rows) arr.add(toWeatherRow(w));
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("rows", arr);
        wrapper.put("date_from", dateFrom.toString());
        wrapper.put("date_to", dateTo.toString());
        wrapper.put("matched", rows.size());
        return ToolResult.ok(String.format("%d weather entr%s between %s and %s.",
                rows.size(), rows.size() == 1 ? "y" : "ies", dateFrom, dateTo), wrapper);
    }

    private ToolResult opByProject(UUID projectId, LocalDate dateFrom, LocalDate dateTo) {
        List<DailyWeather> rows = weatherRepository.findByProjectIdAndLogDateBetweenOrderByLogDateAscIdAsc(
                projectId, dateFrom, dateTo);
        Map<String, Integer> dist = new LinkedHashMap<>();
        int withCondition = 0;
        for (DailyWeather w : rows) {
            String cond = w.getWeatherCondition() == null || w.getWeatherCondition().isBlank()
                    ? "(unspecified)"
                    : w.getWeatherCondition().trim();
            dist.merge(cond, 1, Integer::sum);
            if (w.getWeatherCondition() != null && !w.getWeatherCondition().isBlank()) withCondition++;
        }
        // Also fold in DPR-reported weather for dates without a DailyWeather row
        List<DailyProgressReport> dprs = dprRepository.findByProjectIdAndApprovalStatusAndReportDateBetweenOrderByReportDateAscIdAsc(
                projectId, DprApprovalStatus.APPROVED, dateFrom, dateTo);
        java.util.Set<LocalDate> haveWeather = new java.util.HashSet<>();
        for (DailyWeather w : rows) if (w.getLogDate() != null) haveWeather.add(w.getLogDate());
        Map<String, Integer> dprDist = new LinkedHashMap<>();
        for (DailyProgressReport d : dprs) {
            if (d.getReportDate() == null || haveWeather.contains(d.getReportDate())) continue;
            String cond = d.getWeatherCondition() == null || d.getWeatherCondition().isBlank()
                    ? "(unspecified)"
                    : d.getWeatherCondition().trim();
            dprDist.merge(cond, 1, Integer::sum);
        }
        ArrayNode rowsArr = objectMapper.createArrayNode();
        dist.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> {
                    ObjectNode n = objectMapper.createObjectNode();
                    n.put("weather_condition", e.getKey());
                    n.put("count", e.getValue());
                    n.put("source", "DailyWeather");
                    rowsArr.add(n);
                });
        for (Map.Entry<String, Integer> e : dprDist.entrySet()) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("weather_condition", e.getKey());
            n.put("count", e.getValue());
            n.put("source", "DPR fallback");
            rowsArr.add(n);
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("rows", rowsArr);
        wrapper.put("date_from", dateFrom.toString());
        wrapper.put("date_to", dateTo.toString());
        wrapper.put("daily_weather_rows", rows.size());
        wrapper.put("dpr_fallback_dates", dprDist.values().stream().mapToInt(Integer::intValue).sum());
        wrapper.put("with_condition", withCondition);
        return ToolResult.ok(String.format("%d distinct weather condition%s in window.",
                dist.size() + dprDist.size(),
                (dist.size() + dprDist.size()) == 1 ? "" : "s"), wrapper);
    }

    private ToolResult opLinkToDpr(UUID projectId, LocalDate dateFrom, LocalDate dateTo) {
        List<DailyWeather> weather = weatherRepository.findByProjectIdAndLogDateBetweenOrderByLogDateAscIdAsc(
                projectId, dateFrom, dateTo);
        List<DailyProgressReport> dprs = dprRepository.findByProjectIdAndApprovalStatusAndReportDateBetweenOrderByReportDateAscIdAsc(
                projectId, DprApprovalStatus.APPROVED, dateFrom, dateTo);

        // Group DPRs by date
        Map<LocalDate, List<DailyProgressReport>> dprByDate = new HashMap<>();
        for (DailyProgressReport d : dprs) {
            if (d.getReportDate() == null) continue;
            dprByDate.computeIfAbsent(d.getReportDate(), k -> new ArrayList<>()).add(d);
        }

        // Merge: union of dates from both sources
        java.util.SortedSet<LocalDate> dates = new java.util.TreeSet<>();
        for (DailyWeather w : weather) if (w.getLogDate() != null) dates.add(w.getLogDate());
        dates.addAll(dprByDate.keySet());

        Map<LocalDate, DailyWeather> weatherByDate = new HashMap<>();
        for (DailyWeather w : weather) if (w.getLogDate() != null) weatherByDate.put(w.getLogDate(), w);

        ArrayNode rowsArr = objectMapper.createArrayNode();
        int totalDpr = 0;
        for (LocalDate d : dates) {
            DailyWeather w = weatherByDate.get(d);
            List<DailyProgressReport> ds = dprByDate.getOrDefault(d, List.of());
            String dprWeatherSample = ds.stream()
                    .map(DailyProgressReport::getWeatherCondition)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst().orElse(null);
            ObjectNode n = objectMapper.createObjectNode();
            n.put("date", d.toString());
            n.put("weather_id", w == null || w.getId() == null ? null : w.getId().toString());
            n.put("weather_condition", w == null ? null : w.getWeatherCondition());
            n.put("temp_max_c", w == null ? null : w.getTempMaxC());
            n.put("temp_min_c", w == null ? null : w.getTempMinC());
            n.put("rainfall_mm", w == null ? null : w.getRainfallMm());
            n.put("working_hours", w == null ? null : w.getWorkingHours());
            n.put("dpr_count", ds.size());
            n.put("dpr_weather_sample", dprWeatherSample);
            rowsArr.add(n);
            totalDpr += ds.size();
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("rows", rowsArr);
        wrapper.put("date_from", dateFrom.toString());
        wrapper.put("date_to", dateTo.toString());
        wrapper.put("date_count", dates.size());
        wrapper.put("weather_row_count", weather.size());
        wrapper.put("dpr_count", totalDpr);
        return ToolResult.ok(String.format("%d distinct date%s with %d DPR%s and %d weather row%s.",
                dates.size(), dates.size() == 1 ? "" : "s",
                totalDpr, totalDpr == 1 ? "" : "s",
                weather.size(), weather.size() == 1 ? "" : "s"), wrapper);
    }

    private ObjectNode toWeatherRow(DailyWeather w) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("weather_id", w.getId() == null ? null : w.getId().toString());
        n.put("log_date", w.getLogDate() == null ? null : w.getLogDate().toString());
        n.put("weather_condition", w.getWeatherCondition());
        n.put("temp_max_c", w.getTempMaxC());
        n.put("temp_min_c", w.getTempMinC());
        n.put("rainfall_mm", w.getRainfallMm());
        n.put("wind_kmh", w.getWindKmh());
        n.put("working_hours", w.getWorkingHours());
        n.put("remarks", w.getRemarks());
        return n;
    }

    private static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return LocalDate.parse(raw.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static String orDefault(String s, String d) {
        return s == null || s.isBlank() ? d : s.trim();
    }
}
