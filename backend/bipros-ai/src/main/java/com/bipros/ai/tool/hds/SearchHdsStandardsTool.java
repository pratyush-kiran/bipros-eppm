package com.bipros.ai.tool.hds;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.hds.application.retrieval.Citation;
import com.bipros.hds.application.retrieval.RetrievalAnswer;
import com.bipros.hds.application.retrieval.RetrievalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tool that performs agentic retrieval-augmented answering against the
 * Highway Design Standard (HDS) knowledge base. Delegates to
 * {@link RetrievalService} which runs the plan → retrieve → examine → draft →
 * verify loop over the user-selected HDS document versions and returns an
 * answer with structured citations.
 *
 * <p>The orchestrator routes here deterministically when the chat request
 * carries a non-empty {@code hdsVersionIds} list — Track C wires that branch.
 * Tools never invoke this directly via the LLM tool-selection loop because
 * the model would not reliably emit version UUIDs.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SearchHdsStandardsTool implements Tool {

    private final RetrievalService retrieval;
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public String name() {
        return "search_hds_standards";
    }

    @Override
    public String description() {
        return "Look up Highway Design Standard (HDS) information from the user-selected HDS document versions. "
                + "All factual claims will be grounded in cited chunks from those documents. Use when the user "
                + "has selected HDS scope and asks about engineering standards, dimensions, requirements, or "
                + "any normative content from highway design publications.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("question")
                .put("type", "string")
                .put("description", "The user question, possibly rephrased.");
        ObjectNode versions = props.putObject("selected_version_ids");
        versions.put("type", "array")
                .put("description", "UUIDs of the HDS versions the user has selected.");
        versions.putObject("items").put("type", "string");
        props.putObject("max_rounds")
                .put("type", "integer")
                .put("description", "Max ReAct iteration rounds (default 2).");
        ArrayNode required = schema.putArray("required");
        required.add("question");
        required.add("selected_version_ids");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, AiContext ctx) {
        String question = input.path("question").asText("").trim();
        if (question.isEmpty()) {
            return ToolResult.error("Missing 'question' input");
        }

        List<UUID> versionIds = new ArrayList<>();
        JsonNode versionsNode = input.path("selected_version_ids");
        if (versionsNode.isArray()) {
            for (JsonNode n : versionsNode) {
                String raw = n.asText("").trim();
                if (raw.isEmpty()) continue;
                try {
                    versionIds.add(UUID.fromString(raw));
                } catch (IllegalArgumentException ex) {
                    return ToolResult.error("Invalid version UUID: " + raw);
                }
            }
        }
        if (versionIds.isEmpty()) {
            return ToolResult.error("selected_version_ids must contain at least one version UUID");
        }

        int maxRounds = input.path("max_rounds").asInt(2);
        UUID userId = ctx == null ? null : ctx.userId();
        // TODO(hds-ctx-accessors): wire conversationId from AiContext when accessor lands
        UUID conversationId = null;

        RetrievalAnswer answer;
        try {
            answer = retrieval.answer(question, versionIds, maxRounds, userId, conversationId, null);
        } catch (RuntimeException e) {
            log.warn("HDS retrieval failed: {}", e.getMessage(), e);
            return ToolResult.error("HDS retrieval failed: " + e.getMessage());
        }

        ObjectNode body = om.createObjectNode();
        body.put("answer", answer.answer());

        ArrayNode cites = body.putArray("citations");
        for (Citation c : answer.citations()) {
            ObjectNode m = cites.addObject();
            m.put("marker", c.marker());
            m.put("chunk_id", c.chunkId().toString());
            m.put("version_id", c.versionId().toString());
            m.put("version_label", c.versionLabel());
            m.put("section_path", c.sectionPath());
            m.put("page_start", c.pageStart());
            m.put("page_end", c.pageEnd());
            m.put("excerpt", c.excerpt());
        }

        body.put("verifier_passed", answer.verifier() != null && answer.verifier().passed());

        if (answer.metadata() != null) {
            ObjectNode meta = body.putObject("metadata");
            answer.metadata().forEach((k, v) -> meta.set(k, om.valueToTree(v)));
        }

        String summary = answer.answer();
        return ToolResult.ok(summary, body);
    }
}
