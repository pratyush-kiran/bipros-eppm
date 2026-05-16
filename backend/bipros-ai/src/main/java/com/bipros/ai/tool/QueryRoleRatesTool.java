package com.bipros.ai.tool;

import com.bipros.ai.context.AiContext;
import com.bipros.resource.application.service.role.RoleRateResolver;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.role.EquipmentRoleVariant;
import com.bipros.resource.domain.model.role.ManpowerRoleRate;
import com.bipros.resource.domain.model.role.MaterialRoleVariant;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.bipros.resource.domain.repository.role.EquipmentRoleVariantRepository;
import com.bipros.resource.domain.repository.role.ManpowerRoleRateRepository;
import com.bipros.resource.domain.repository.role.MaterialRoleVariantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolve the effective rate(s) for a {@link ResourceRole} on the current project.
 * Walks the override chain via {@link RoleRateResolver} (project override → variant rate → null)
 * for every variant under the role, so the AI sees both currently-overridden and base rates
 * side by side. This is the canonical entry point for "what rate is X charged at on Project Y"
 * style questions — the analytics warehouse cannot see project overrides.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRoleRatesTool implements Tool {

    private final ResourceRoleRepository roleRepo;
    private final ManpowerRoleRateRepository manpowerRepo;
    private final EquipmentRoleVariantRepository equipmentRepo;
    private final MaterialRoleVariantRepository materialRepo;
    private final RoleRateResolver resolver;
    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "query_role_rates";
    }

    @Override
    public String description() {
        return "Resolve the effective rate for a Resource Role on the current project. "
                + "Takes role_code (e.g. \"MASON-101\", \"BNK-ROLE-CONSTRUCTIONMANAGER\", "
                + "\"excavator-1\"). Returns every variant under the role with override_rate, "
                + "variant_rate, effective_rate, source (OVERRIDE | VARIANT | NONE), unit, and "
                + "human-readable variant qualifier (category+grade | make+model | spec_grade). "
                + "Use this for ANY rate / variant / override question — the analytics warehouse "
                + "cannot see project overrides. Requires a project in scope.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();

        ObjectNode roleCode = mapper.createObjectNode();
        roleCode.put("type", "string");
        roleCode.put("description", "Role code, e.g. MASON-101, BNK-ROLE-CONSTRUCTIONMANAGER, excavator-1.");
        props.set("role_code", roleCode);

        ObjectNode variantFilter = mapper.createObjectNode();
        variantFilter.put("type", "string");
        variantFilter.put("description",
                "Optional case-insensitive substring to narrow the returned variants "
                        + "(matches against the variant qualifier — e.g. \"Skilled\", \"Grade A\", "
                        + "\"JCB\", \"Premium\", \"OPC 53\"). Omit to return all variants for the role.");
        props.set("variant_filter", variantFilter);

        schema.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("role_code");
        schema.set("required", required);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        String roleCode = text(input, "role_code");
        if (roleCode == null || roleCode.isBlank()) {
            return ToolResult.error("role_code is required.");
        }
        String filter = text(input, "variant_filter");
        String filterLc = filter == null ? null : filter.trim().toLowerCase();

        UUID projectId = ctx.projectId();
        Optional<ResourceRole> roleOpt = roleRepo.findByCode(roleCode);
        if (roleOpt.isEmpty()) {
            return ToolResult.error("No role found for code: " + roleCode);
        }
        ResourceRole role = roleOpt.get();
        String typeCode = role.getResourceType() != null
                ? role.getResourceType().getCode() : null;
        if (typeCode == null) {
            return ToolResult.error("Role " + roleCode + " has no resource type bound.");
        }

        ArrayNode rows = mapper.createArrayNode();
        int matched = 0;
        switch (typeCode.toUpperCase()) {
            case "LABOR", "MANPOWER" -> {
                List<ManpowerRoleRate> vs = manpowerRepo.findByRoleIdAndActiveTrue(role.getId());
                for (ManpowerRoleRate v : vs) {
                    String qualifier = "category=" + v.getCategoryId() + " grade=" + v.getGradeId();
                    if (filterLc != null && !qualifier.toLowerCase().contains(filterLc)) continue;
                    rows.add(row(v.getId(), "MANPOWER", qualifier, v.getUnit(), v.getRate(),
                            projectId, "MANPOWER"));
                    matched++;
                }
            }
            case "EQUIPMENT" -> {
                List<EquipmentRoleVariant> vs = equipmentRepo.findByRoleIdAndActiveTrue(role.getId());
                for (EquipmentRoleVariant v : vs) {
                    String qualifier = v.getMake() + " / " + v.getModel();
                    if (filterLc != null && !qualifier.toLowerCase().contains(filterLc)) continue;
                    rows.add(row(v.getId(), "EQUIPMENT", qualifier, v.getUnit(), v.getRate(),
                            projectId, "EQUIPMENT"));
                    matched++;
                }
            }
            case "MATERIAL" -> {
                List<MaterialRoleVariant> vs = materialRepo.findByRoleIdAndActiveTrue(role.getId());
                for (MaterialRoleVariant v : vs) {
                    String qualifier = v.getSpecGrade();
                    if (filterLc != null && qualifier != null
                            && !qualifier.toLowerCase().contains(filterLc)) continue;
                    rows.add(row(v.getId(), "MATERIAL", qualifier, v.getUnit(), v.getRate(),
                            projectId, "MATERIAL"));
                    matched++;
                }
            }
            default -> {
                return ToolResult.error("Unsupported resource type: " + typeCode);
            }
        }

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.put("role_id", role.getId().toString());
        wrapper.put("role_code", role.getCode());
        wrapper.put("role_name", role.getName());
        wrapper.put("resource_type", typeCode);
        wrapper.put("project_id", projectId != null ? projectId.toString() : null);
        wrapper.put("variant_count", matched);
        wrapper.set("rates", rows);

        String summary = "Found " + matched + " variant rate" + (matched == 1 ? "" : "s")
                + " for role " + role.getCode() + ".";
        return ToolResult.ok(summary, wrapper);
    }

    private ObjectNode row(UUID variantId, String variantType, String qualifier,
                           String unit, BigDecimal variantRate,
                           UUID projectId, String resolverCode) {
        ObjectNode row = mapper.createObjectNode();
        row.put("variant_id", variantId.toString());
        row.put("variant_type", variantType);
        row.put("variant", qualifier != null ? qualifier : "");
        row.put("unit", unit != null ? unit : "");
        row.put("variant_rate", variantRate != null ? variantRate.toPlainString() : null);

        BigDecimal effective = resolver.resolveRate(projectId, resolverCode, variantId);
        boolean overridden = resolver.hasOverride(projectId, resolverCode, variantId);
        String source = effective == null ? "NONE" : (overridden ? "OVERRIDE" : "VARIANT");
        row.put("effective_rate", effective != null ? effective.toPlainString() : null);
        row.put("override_rate", overridden ? (effective != null ? effective.toPlainString() : null) : null);
        row.put("source", source);
        if (effective == null) {
            row.put("warning", "rate_not_set_for_variant");
        }
        return row;
    }

    private static String text(JsonNode in, String field) {
        JsonNode n = in == null ? null : in.path(field);
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }
}
