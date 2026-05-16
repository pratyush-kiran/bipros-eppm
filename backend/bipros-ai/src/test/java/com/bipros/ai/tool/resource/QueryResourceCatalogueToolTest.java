package com.bipros.ai.tool.resource;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.ToolResult;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceStatus;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies that the catalogue tool returns priced rows from
 * {@code resource.resources} regardless of whether they are assigned to any
 * activity — the bug it was created to fix is that the AI's old
 * resource-lookup tools see only assignments, so a fully-priced project with
 * no assignments looked empty.
 */
class QueryResourceCatalogueToolTest {

    private ResourceRepository resourceRepository;
    private QueryResourceCatalogueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        resourceRepository = Mockito.mock(ResourceRepository.class);
        mapper = new ObjectMapper();
        tool = new QueryResourceCatalogueTool(resourceRepository, mapper);
    }

    private static AiContext ctx() {
        return new AiContext(UUID.randomUUID(), UUID.randomUUID(), "general", "ADMIN", "ADMIN",
                Collections.emptyList());
    }

    private static ResourceType type(String code, String name) {
        ResourceType t = new ResourceType();
        t.setCode(code);
        t.setName(name);
        return t;
    }

    private static ResourceRole role(String code, String name) {
        ResourceRole r = new ResourceRole();
        r.setCode(code);
        r.setName(name);
        return r;
    }

    private static Resource resource(String code, String name, ResourceType t, ResourceRole r,
                                     String costPerUnit, String unit) {
        Resource res = Resource.builder()
                .code(code)
                .name(name)
                .resourceType(t)
                .role(r)
                .unit(unit)
                .costPerUnit(new BigDecimal(costPerUnit))
                .status(ResourceStatus.ACTIVE)
                .build();
        res.setId(UUID.randomUUID());
        return res;
    }

    @Test
    void countsByTypeReportsCatalogueSizeBrokenDownByType() {
        ResourceType machine = type("MACHINE", "Machine");
        ResourceType manpower = type("MANPOWER", "Manpower");
        ResourceType material = type("MATERIAL", "Material");
        ResourceRole roleEq = role("EQ", "Equipment");
        ResourceRole roleMp = role("MP", "Manpower");
        ResourceRole roleMt = role("MT", "Material");

        when(resourceRepository.findAll()).thenReturn(List.of(
                resource("EXC-20T", "Excavator 20T", machine, roleEq, "4250000", "day"),
                resource("PILE-BG28", "Piling Rig Bauer BG28", machine, roleEq, "6800000", "day"),
                resource("MASON-1", "Mason", manpower, roleMp, "1550000", "day"),
                resource("LABR-1", "Unskilled Labour", manpower, roleMp, "2550000", "day"),
                resource("TMT-FE500D", "TMT Rebar Fe500D", material, roleMt, "12250000", "MT")
        ));

        ObjectNode input = mapper.createObjectNode();
        input.put("status", "ALL"); // exercise the all-status path
        ToolResult result = tool.execute(input, ctx());

        assertThat(result.success()).isTrue();
        JsonNode data = result.data();
        assertThat(data.get("matched").asInt()).isEqualTo(5);
        JsonNode counts = data.get("counts_by_type");
        assertThat(counts).hasSize(3);
        // Confirm each canonical type appears with the expected count.
        long machineCount = countOf(counts, "MACHINE");
        long manpowerCount = countOf(counts, "MANPOWER");
        long materialCount = countOf(counts, "MATERIAL");
        assertThat(machineCount).isEqualTo(2);
        assertThat(manpowerCount).isEqualTo(2);
        assertThat(materialCount).isEqualTo(1);
    }

    @Test
    void mostExpensiveEquipmentTopByRateWorks() {
        ResourceType machine = type("MACHINE", "Machine");
        ResourceRole eq = role("EQ", "Equipment");

        when(resourceRepository.findByResourceType_CodeAndStatus("MACHINE", ResourceStatus.ACTIVE))
                .thenReturn(List.of(
                        resource("EXC-20T", "Excavator 20T", machine, eq, "4250000", "day"),
                        resource("PILE-BG28", "Piling Rig Bauer BG28", machine, eq, "6800000", "day"),
                        resource("CRN-50T", "Crawler Crane 50T", machine, eq, "3200000", "day")
                ));

        ObjectNode input = mapper.createObjectNode();
        input.put("type_code", "EQUIPMENT"); // alias for MACHINE
        input.put("order_by", "cost_desc");
        ToolResult result = tool.execute(input, ctx());

        assertThat(result.success()).isTrue();
        JsonNode top = result.data().get("top_by_rate");
        assertThat(top.get(0).get("name").asText()).isEqualTo("Piling Rig Bauer BG28");
        assertThat(top.get(0).get("cost_per_unit").asText()).isEqualTo("6800000");
        assertThat(top.get(0).get("unit").asText()).isEqualTo("day");
        // Summary should mention the most expensive entry by name.
        assertThat(result.summary()).contains("Piling Rig Bauer BG28");
        // The alias resolution surfaces in the wrapper.
        assertThat(result.data().get("type_code_input").asText()).isEqualTo("EQUIPMENT");
        assertThat(result.data().get("type_code_resolved").asText()).isEqualTo("MACHINE");
    }

    @Test
    void exactCodeLookupReturnsSingleRow() {
        ResourceType material = type("MATERIAL", "Material");
        ResourceRole rebar = role("REBAR", "Rebar");

        when(resourceRepository.findByResourceType_CodeAndStatus("MATERIAL", ResourceStatus.ACTIVE))
                .thenReturn(List.of(
                        resource("TMT-FE500D", "TMT Rebar Fe500D", material, rebar, "12250000", "MT"),
                        resource("CEM-OPC53", "OPC 53 Cement", material, rebar, "350000", "MT")
                ));

        ObjectNode input = mapper.createObjectNode();
        input.put("type_code", "MATERIAL");
        input.put("code", "TMT-FE500D");
        ToolResult result = tool.execute(input, ctx());

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("matched").asInt()).isEqualTo(1);
        JsonNode row = result.data().get("rows").get(0);
        assertThat(row.get("cost_per_unit").asText()).isEqualTo("12250000");
        assertThat(row.get("unit").asText()).isEqualTo("MT");
    }

    @Test
    void nameFilterMatchesNameOrCode() {
        ResourceType machine = type("MACHINE", "Machine");
        ResourceRole eq = role("EQ", "Equipment");

        when(resourceRepository.findByResourceType_CodeAndStatus("MACHINE", ResourceStatus.ACTIVE))
                .thenReturn(List.of(
                        resource("EXC-20T", "Excavator 20T", machine, eq, "4250000", "day"),
                        resource("EXC-30T", "Excavator 30T", machine, eq, "5500000", "day"),
                        resource("CRN-50T", "Crawler Crane 50T", machine, eq, "3200000", "day")
                ));

        ObjectNode input = mapper.createObjectNode();
        input.put("type_code", "EQUIPMENT");
        input.put("name_filter", "excavator");
        ToolResult result = tool.execute(input, ctx());

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("matched").asInt()).isEqualTo(2);
    }

    @Test
    void aliasCanonicalisationAcceptsLabourLaborManpower() {
        assertThat(QueryResourceCatalogueTool.canonicaliseTypeCode("EQUIPMENT")).isEqualTo("MACHINE");
        assertThat(QueryResourceCatalogueTool.canonicaliseTypeCode("equipment")).isEqualTo("MACHINE");
        assertThat(QueryResourceCatalogueTool.canonicaliseTypeCode("LABOR")).isEqualTo("MANPOWER");
        assertThat(QueryResourceCatalogueTool.canonicaliseTypeCode("Labour")).isEqualTo("MANPOWER");
        assertThat(QueryResourceCatalogueTool.canonicaliseTypeCode("MANPOWER")).isEqualTo("MANPOWER");
        assertThat(QueryResourceCatalogueTool.canonicaliseTypeCode("MATERIAL")).isEqualTo("MATERIAL");
        assertThat(QueryResourceCatalogueTool.canonicaliseTypeCode("PLANT")).isEqualTo("MACHINE");
        assertThat(QueryResourceCatalogueTool.canonicaliseTypeCode("")).isNull();
        assertThat(QueryResourceCatalogueTool.canonicaliseTypeCode(null)).isNull();
    }

    @Test
    void emptyCatalogueReturnsHelpfulSummary() {
        when(resourceRepository.findByResourceType_CodeAndStatus(any(), any()))
                .thenReturn(List.of());

        ObjectNode input = mapper.createObjectNode();
        input.put("type_code", "EQUIPMENT");
        input.put("name_filter", "nonexistent");
        ToolResult result = tool.execute(input, ctx());

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("matched").asInt()).isZero();
        assertThat(result.summary()).contains("No catalogue rows match");
    }

    @Test
    void includeZeroCostFalseDropsUnpricedRows() {
        ResourceType machine = type("MACHINE", "Machine");
        ResourceRole eq = role("EQ", "Equipment");
        Resource priced = resource("EXC-20T", "Excavator 20T", machine, eq, "4250000", "day");
        Resource unpriced = Resource.builder()
                .code("UNK-1")
                .name("Untracked Machine")
                .resourceType(machine)
                .role(eq)
                .unit("day")
                .costPerUnit(BigDecimal.ZERO)
                .status(ResourceStatus.ACTIVE)
                .build();
        unpriced.setId(UUID.randomUUID());

        when(resourceRepository.findByResourceType_CodeAndStatus("MACHINE", ResourceStatus.ACTIVE))
                .thenReturn(List.of(priced, unpriced));

        ObjectNode input = mapper.createObjectNode();
        input.put("type_code", "EQUIPMENT");
        input.put("include_zero_cost", false);
        ToolResult result = tool.execute(input, ctx());

        assertThat(result.data().get("matched").asInt()).isEqualTo(1);
        JsonNode row = result.data().get("rows").get(0);
        assertThat(row.get("code").asText()).isEqualTo("EXC-20T");
    }

    private static long countOf(JsonNode countsByType, String typeCode) {
        for (JsonNode n : countsByType) {
            if (typeCode.equals(n.get("type_code").asText())) return n.get("count").asLong();
        }
        return -1;
    }
}
