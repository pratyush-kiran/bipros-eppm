package com.bipros.ai.tool;

import com.bipros.ai.context.AiContext;
import com.bipros.resource.application.service.role.NormBudgeted;
import com.bipros.resource.application.service.role.RoleProductivityNormResolver;
import com.bipros.resource.domain.model.ProductivityNormType;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolve the productivity norm for a (work activity, role, variant) tuple via the canonical
 * 3-tier chain (variant → role → unscoped). Wraps {@link RoleProductivityNormResolver}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryProductivityNormTool implements Tool {

    private final RoleProductivityNormResolver resolver;
    private final WorkActivityRepository workActivityRepo;
    private final ResourceRoleRepository roleRepo;
    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "query_productivity_norm";
    }

    @Override
    public String description() {
        return "Resolve the budgeted productivity norm for a (work_activity, role, variant) tuple. "
                + "Uses the 3-tier chain: VARIANT (work_activity + role + skill/grade or make/model) "
                + "→ ROLE (work_activity + role) → UNSCOPED (work_activity only). Returns "
                + "output_per_day, output_per_man_per_day, crew_size, working_hours_per_day, "
                + "norm_type, and the resolved tier in `scope`. Inputs: work_activity_code OR "
                + "work_activity_id, optional role_code, optional category_id / grade_id (manpower "
                + "variant) or make / model (equipment variant), norm_type (MANPOWER | EQUIPMENT).";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        addStringProp(props, "work_activity_code", "Master library code (e.g. BLINDING).");
        addStringProp(props, "work_activity_id", "UUID — used when code is absent.");
        addStringProp(props, "role_code", "Optional role code to narrow the lookup.");
        addStringProp(props, "category_id", "Optional manpower-variant category UUID.");
        addStringProp(props, "grade_id", "Optional manpower-variant grade UUID.");
        addStringProp(props, "make", "Optional equipment make.");
        addStringProp(props, "model", "Optional equipment model.");
        addStringProp(props, "norm_type", "MANPOWER (default) or EQUIPMENT.");
        schema.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        schema.set("required", required);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        String workActivityCode = text(input, "work_activity_code");
        String workActivityIdStr = text(input, "work_activity_id");

        UUID workActivityId = null;
        WorkActivity wa = null;
        if (workActivityIdStr != null) {
            try {
                workActivityId = UUID.fromString(workActivityIdStr);
            } catch (IllegalArgumentException e) {
                return ToolResult.error("work_activity_id is not a UUID: " + workActivityIdStr);
            }
            wa = workActivityRepo.findById(workActivityId).orElse(null);
        } else if (workActivityCode != null) {
            wa = workActivityRepo.findByCode(workActivityCode).orElse(null);
            if (wa != null) workActivityId = wa.getId();
        } else {
            return ToolResult.error("Either work_activity_code or work_activity_id is required.");
        }
        if (wa == null) {
            return ToolResult.error("WorkActivity not found.");
        }

        UUID roleId = null;
        String roleCode = text(input, "role_code");
        ResourceRole role = null;
        if (roleCode != null) {
            Optional<ResourceRole> r = roleRepo.findByCode(roleCode);
            if (r.isEmpty()) {
                return ToolResult.error("Role not found: " + roleCode);
            }
            role = r.get();
            roleId = role.getId();
        }

        UUID categoryId = parseUuid(text(input, "category_id"));
        UUID gradeId = parseUuid(text(input, "grade_id"));
        String make = text(input, "make");
        String model = text(input, "model");
        String normTypeStr = text(input, "norm_type");
        ProductivityNormType normType;
        try {
            normType = normTypeStr == null
                    ? ProductivityNormType.MANPOWER
                    : ProductivityNormType.valueOf(normTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("norm_type must be MANPOWER or EQUIPMENT.");
        }

        NormBudgeted budgeted = resolver.resolveAsBudgeted(
                workActivityId, roleId, categoryId, gradeId, make, model, normType);

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.put("work_activity_id", workActivityId.toString());
        wrapper.put("work_activity_code", wa.getCode());
        wrapper.put("work_activity_name", wa.getName());
        wrapper.put("default_unit", wa.getDefaultUnit() != null ? wa.getDefaultUnit() : "");
        wrapper.put("role_id", roleId != null ? roleId.toString() : null);
        wrapper.put("role_code", role != null ? role.getCode() : null);
        wrapper.put("norm_type", normType.name());
        wrapper.put("scope", budgeted == null ? "NONE" : budgeted.source());
        if (budgeted != null && budgeted.normId() != null) {
            wrapper.put("norm_id", budgeted.normId().toString());
            wrapper.put("output_per_day",
                    budgeted.outputPerDay() != null ? budgeted.outputPerDay().toPlainString() : null);
            wrapper.put("output_per_man_per_day",
                    budgeted.outputPerManPerDay() != null
                            ? budgeted.outputPerManPerDay().toPlainString() : null);
            wrapper.put("output_per_hour",
                    budgeted.outputPerHour() != null
                            ? budgeted.outputPerHour().toPlainString() : null);
            wrapper.put("working_hours_per_day", budgeted.workingHoursPerDay());
        } else {
            wrapper.put("warning",
                    "No productivity norm matches this (work_activity, role, variant) — "
                            + "either define one in Admin → Productivity Norms or this activity "
                            + "is intentionally untracked (e.g. design / office work).");
        }

        String summary = budgeted != null && budgeted.normId() != null
                ? "Norm resolved at " + budgeted.source() + " tier for " + wa.getCode()
                : "No norm found for " + wa.getCode();
        return ToolResult.ok(summary, wrapper);
    }

    private void addStringProp(ObjectNode props, String name, String description) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "string");
        n.put("description", description);
        props.set(name, n);
    }

    private static String text(JsonNode in, String field) {
        JsonNode n = in == null ? null : in.path(field);
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
