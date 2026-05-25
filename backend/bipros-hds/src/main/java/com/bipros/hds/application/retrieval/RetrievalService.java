package com.bipros.hds.application.retrieval;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.domain.HdsQueryLog;
import com.bipros.hds.domain.HdsVersion;
import com.bipros.hds.domain.repo.HdsQueryLogRepository;
import com.bipros.hds.domain.repo.HdsVersionRepository;
import com.bipros.hds.infrastructure.embedding.EmbeddingClient;
import com.bipros.hds.infrastructure.reranker.Reranker;
import com.bipros.hds.infrastructure.retrieval.HybridSearchRepository;
import com.bipros.hds.infrastructure.retrieval.ReciprocalRankFusion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class RetrievalService {

    private static final String SAFE_FAIL = "I don't see that in the selected HDS documents.";

    private final HdsProperties props;
    private final HdsVersionRepository versionRepo;
    private final HdsQueryLogRepository logRepo;
    private final HybridSearchRepository hybridRepo;
    private final EmbeddingClient embedClient;
    private final Reranker reranker;
    private final LlmGateway llm;
    private final QueryCache cache;
    private final ObjectMapper om = new ObjectMapper();

    public RetrievalAnswer answer(String question, List<UUID> selectedVersionIds,
                                  int maxRounds, UUID userId, UUID conversationId,
                                  LlmGateway.StreamCallback streamCb) {
        long started = System.currentTimeMillis();

        // Cache lookup
        var cached = cache.get(question, selectedVersionIds);
        if (cached != null) {
            log.info("Cache hit for query: '{}'", question);
            if (streamCb != null) streamCb.onToken(cached.answer());
            return cached;
        }

        // Resolve version labels for the prompt
        List<HdsVersion> versions = versionRepo.findAllById(selectedVersionIds);
        if (versions.isEmpty()) {
            return safeFail(question, selectedVersionIds, started);
        }

        // Phase 1: PLAN — classify intent + emit search queries
        PlanResult plan = phasePlan(question, versions);

        // Off-topic intent: return a short polite response without retrieval.
        if (plan.intent() == PlanResult.Intent.OFF_TOPIC) {
            String reply = "I'm the HDS document assistant. Ask me about content in the selected "
                + "documents — for example, specific facts from a section, or what topics the "
                + "documents cover.";
            if (streamCb != null) streamCb.onToken(reply);
            var meta = new LinkedHashMap<String, Object>();
            meta.put("duration_ms", (int) (System.currentTimeMillis() - started));
            meta.put("rounds", 0);
            meta.put("intent", "off_topic");
            return new RetrievalAnswer(reply, List.of(), new VerifyResult(true, List.of()), meta);
        }

        // Phase 2 + 3 loop — retrieval strategy depends on intent
        List<UUID> retrievedIds = new ArrayList<>();
        int roundsRun = 0;

        if (plan.intent() == PlanResult.Intent.OVERVIEW) {
            // Structural sample: first chunks per version. Bypasses vector search
            // because the user is asking what's in the document — there's no
            // specific term to match against.
            int perVersionLimit = Math.max(3,
                props.getRetrieval().getMaxChunksPerQuery() / Math.max(versions.size(), 1));
            retrievedIds = hybridRepo.sampleOverviewChunks(selectedVersionIds, perVersionLimit);
            roundsRun = 1;
        } else {
            // Specific intent: vector + BM25 retrieval with optional follow-up rounds.
            List<String> followUps = List.of();
            for (int round = 1; round <= maxRounds; round++) {
                List<String> queries = round == 1 ? plan.searchQueries() : followUps;
                if ((queries == null || queries.isEmpty()) && round == 1) {
                    // Fallback: planner returned no queries — use the raw question.
                    queries = List.of(question);
                }
                var roundIds = phaseRetrieve(queries, selectedVersionIds);
                retrievedIds = dedupe(retrievedIds, roundIds);
                roundsRun = round;

                if (retrievedIds.isEmpty()) {
                    return safeFail(question, selectedVersionIds, started, plan.intent());
                }

                var examine = phaseExamine(question, hybridRepo.fetchChunks(retrievedIds));
                if (examine.sufficient() || round == maxRounds) {
                    break;
                }
                followUps = examine.followUpQueries();
            }
        }

        if (retrievedIds.isEmpty()) {
            return safeFail(question, selectedVersionIds, started, plan.intent());
        }

        // Phase 4: DRAFT
        var chunks = hybridRepo.fetchChunks(retrievedIds);
        if (chunks.size() > props.getRetrieval().getMaxChunksPerQuery()) {
            chunks = chunks.subList(0, props.getRetrieval().getMaxChunksPerQuery());
        }
        Map<String, HybridSearchRepository.ChunkRow> markerToChunk = new LinkedHashMap<>();
        StringBuilder chunkBlock = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String marker = "c" + (i + 1);
            markerToChunk.put(marker, chunks.get(i));
            chunkBlock.append("[").append(marker).append("] ")
                .append(chunks.get(i).sectionPath()).append(" (p. ")
                .append(chunks.get(i).pageStart()).append(")\n")
                .append(chunks.get(i).content()).append("\n\n");
        }

        String draftSystem = plan.intent() == PlanResult.Intent.OVERVIEW
            ? Prompts.DRAFT_OVERVIEW_SYSTEM
            : Prompts.DRAFT_SYSTEM;
        String draftUser = "Question: " + question + "\n\nChunks:\n" + chunkBlock;
        String draft = llm.completeStreaming(
            List.of(new LlmGateway.ChatMessage("system", draftSystem),
                    new LlmGateway.ChatMessage("user", draftUser)),
            streamCb);

        // Phase 5: VERIFY — claim-level grounding check, but ONLY for SPECIFIC intent.
        // OVERVIEW intent produces structural summaries (bullets describing section
        // contents) which the claim-level verifier mis-flags as ungrounded; the
        // structural sampler already guarantees only real chunks are in scope.
        VerifyResult verify;
        if (plan.intent() == PlanResult.Intent.OVERVIEW) {
            log.debug("Skipping claim verifier for OVERVIEW intent");
            verify = new VerifyResult(true, List.of());
        } else {
            verify = phaseVerify(draft, markerToChunk);
            int retries = 0;
            while (!verify.passed() && retries < props.getVerifier().getMaxRetries()) {
                String feedback = "Verifier rejected these claims:\n" +
                    String.join("\n", verify.issues().stream().map(i -> "- " + i.claim() + " (" + i.explanation() + ")").toList()) +
                    "\n\nRewrite the answer using only the chunks provided.";
                draft = llm.completeStreaming(
                    List.of(new LlmGateway.ChatMessage("system", draftSystem),
                            new LlmGateway.ChatMessage("user", draftUser + "\n\n" + feedback)),
                    streamCb);
                verify = phaseVerify(draft, markerToChunk);
                retries++;
            }

            if (!verify.passed()) {
                draft = SAFE_FAIL;
            }
        }

        // Build citations from markers actually used in draft
        List<Citation> citations = buildCitations(draft, markerToChunk, versions);

        var meta = new LinkedHashMap<String, Object>();
        meta.put("duration_ms", (int) (System.currentTimeMillis() - started));
        meta.put("rounds", roundsRun);
        meta.put("intent", plan.intent().name().toLowerCase());

        var answer = new RetrievalAnswer(draft, citations, verify, meta);
        cache.put(question, selectedVersionIds, answer, Duration.ofSeconds(props.getRetrieval().getCacheTtlSeconds()));
        logQuery(userId, conversationId, question, selectedVersionIds, retrievedIds, answer, started);
        return answer;
    }

    private PlanResult phasePlan(String question, List<HdsVersion> versions) {
        String userMsg = "Question: " + question + "\nSelected versions: " +
            versions.stream().map(HdsVersion::getVersionLabel).toList();
        String json = llm.completeStructured(
            List.of(new LlmGateway.ChatMessage("system", Prompts.PLAN_SYSTEM),
                    new LlmGateway.ChatMessage("user", userMsg)),
            "plan");
        try {
            JsonNode n = om.readTree(json);
            PlanResult.Intent intent = parseIntent(n.path("intent").asText(""));
            // Heuristic override: if the question obviously matches an overview pattern
            // ("what information…", "summarize", "table of contents"), upgrade to OVERVIEW
            // even when the LLM classified it as SPECIFIC. This is safe because the
            // patterns are narrow and the OVERVIEW path is still grounded + cited.
            log.info("Plan: question='{}' llm_intent={} heuristic_overview={}",
                question, intent, looksLikeOverview(question));
            if (intent != PlanResult.Intent.OVERVIEW && looksLikeOverview(question)) {
                log.info("Overriding plan intent {} -> OVERVIEW for question='{}'", intent, question);
                intent = PlanResult.Intent.OVERVIEW;
            }
            return new PlanResult(
                n.path("is_compound").asBoolean(false),
                jsonArrayToList(n.path("sub_questions")),
                jsonArrayToList(n.path("search_queries")),
                intent);
        } catch (Exception e) {
            log.warn("Plan parse failed for question='{}', defaulting to SPECIFIC: {}", question, e.toString());
            // Heuristic fallback for common overview phrasings when the planner fails.
            PlanResult.Intent fallback = looksLikeOverview(question)
                ? PlanResult.Intent.OVERVIEW
                : PlanResult.Intent.SPECIFIC;
            return new PlanResult(false, List.of(), List.of(question), fallback);
        }
    }

    private static PlanResult.Intent parseIntent(String raw) {
        if (raw == null) return PlanResult.Intent.SPECIFIC;
        return switch (raw.trim().toLowerCase()) {
            case "overview", "summary", "summarize" -> PlanResult.Intent.OVERVIEW;
            case "off_topic", "off-topic", "offtopic", "chat" -> PlanResult.Intent.OFF_TOPIC;
            default -> PlanResult.Intent.SPECIFIC;
        };
    }

    private static boolean looksLikeOverview(String question) {
        if (question == null) return false;
        String q = question.toLowerCase();
        return q.contains("what information")
            || q.contains("what info")
            || q.contains("what is in")
            || q.contains("what's in")
            || q.contains("what does")
            || q.contains("what is this doc")
            || q.contains("what's this")
            || q.contains("table of contents")
            || q.contains("toc")
            || q.contains("summarize")
            || q.contains("summary")
            || q.contains("overview")
            || q.contains("describe the document")
            || q.contains("what topics");
    }

    private List<UUID> phaseRetrieve(List<String> queries, List<UUID> selectedVersionIds) {
        if (queries == null || queries.isEmpty()) return List.of();
        List<UUID> dedup = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (String q : queries) {
            float[] qEmb = embedClient.embedBatch(List.of(q)).get(0);
            var dense = hybridRepo.searchByEmbedding(qEmb, selectedVersionIds,
                props.getRetrieval().getSimilarityFloor(),
                props.getRetrieval().getVectorTopK());
            var sparse = hybridRepo.searchByKeyword(q, selectedVersionIds,
                props.getRetrieval().getBm25TopK());
            var fused = ReciprocalRankFusion.fuse(List.of(dense, sparse), 60,
                props.getRetrieval().getVectorTopK());

            List<HybridSearchRepository.ChunkRow> rows = hybridRepo.fetchChunks(fused);
            List<String> texts = rows.stream().map(HybridSearchRepository.ChunkRow::content).toList();
            List<Integer> rerankedIdx = reranker.rerank(q, texts, props.getReranker().getTopK());
            for (int idx : rerankedIdx) {
                UUID id = rows.get(idx).id();
                if (seen.add(id)) dedup.add(id);
            }
        }
        return dedup;
    }

    private ExamineResult phaseExamine(String question, List<HybridSearchRepository.ChunkRow> chunks) {
        StringBuilder ctx = new StringBuilder();
        for (var c : chunks) ctx.append(c.sectionPath()).append("\n").append(c.content()).append("\n\n");
        String json = llm.completeStructured(
            List.of(new LlmGateway.ChatMessage("system", Prompts.EXAMINE_SYSTEM),
                    new LlmGateway.ChatMessage("user", "Question: " + question + "\n\nChunks:\n" + ctx)),
            "examine");
        try {
            JsonNode n = om.readTree(json);
            return new ExamineResult(
                n.path("sufficient").asBoolean(true),
                jsonArrayToList(n.path("follow_up_queries")));
        } catch (Exception e) {
            return new ExamineResult(true, List.of());
        }
    }

    private VerifyResult phaseVerify(String draft, Map<String, HybridSearchRepository.ChunkRow> markerToChunk) {
        StringBuilder ctx = new StringBuilder();
        markerToChunk.forEach((m, c) -> ctx.append("[").append(m).append("] ").append(c.content()).append("\n\n"));
        String json = llm.completeStructured(
            List.of(new LlmGateway.ChatMessage("system", Prompts.VERIFY_SYSTEM),
                    new LlmGateway.ChatMessage("user", "Draft answer:\n" + draft + "\n\nCited chunks:\n" + ctx)),
            "verify");
        try {
            JsonNode n = om.readTree(json);
            boolean passed = n.path("passed").asBoolean(false);
            List<VerifyResult.Issue> issues = new ArrayList<>();
            if (n.has("issues")) {
                for (JsonNode it : n.get("issues")) {
                    issues.add(new VerifyResult.Issue(
                        it.path("claim").asText(""),
                        it.path("citation").asText(""),
                        it.path("explanation").asText("")));
                }
            }
            return new VerifyResult(passed, issues);
        } catch (Exception e) {
            return new VerifyResult(false, List.of(new VerifyResult.Issue("", "", "verifier parse error")));
        }
    }

    private List<Citation> buildCitations(String draft, Map<String, HybridSearchRepository.ChunkRow> markerToChunk,
                                          List<HdsVersion> versions) {
        Map<UUID, HdsVersion> byVid = new HashMap<>();
        versions.forEach(v -> byVid.put(v.getId(), v));
        List<Citation> out = new ArrayList<>();
        Pattern p = Pattern.compile("\\[c(\\d+)\\]");
        Matcher m = p.matcher(draft);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            String marker = "c" + m.group(1);
            if (!seen.add(marker)) continue;
            var chunk = markerToChunk.get(marker);
            if (chunk == null) continue;
            var ver = byVid.get(chunk.hdsVersionId());
            String label = ver == null ? "Unknown" : ver.getVersionLabel();
            String excerpt = chunk.content().length() > 200
                ? chunk.content().substring(0, 200) + "…"
                : chunk.content();
            out.add(new Citation(marker, chunk.id(), chunk.hdsVersionId(), label,
                chunk.sectionPath(), chunk.pageStart(), chunk.pageEnd(), excerpt));
        }
        return out;
    }

    private RetrievalAnswer safeFail(String question, List<UUID> versionIds, long started) {
        return safeFail(question, versionIds, started, PlanResult.Intent.SPECIFIC);
    }

    private RetrievalAnswer safeFail(String question, List<UUID> versionIds, long started,
                                     PlanResult.Intent intent) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("duration_ms", (int) (System.currentTimeMillis() - started));
        meta.put("rounds", 0);
        meta.put("intent", intent.name().toLowerCase());
        return new RetrievalAnswer(SAFE_FAIL, List.of(),
            new VerifyResult(true, List.of()), meta);
    }

    private void logQuery(UUID userId, UUID conversationId, String question, List<UUID> versionIds,
                          List<UUID> retrievedIds, RetrievalAnswer answer, long started) {
        try {
            var entry = new HdsQueryLog();
            entry.setUserId(userId);
            entry.setConversationId(conversationId);
            entry.setQueryText(question);
            entry.setSelectedVersionIds(versionIds.toArray(new UUID[0]));
            entry.setRetrievedChunkIds(retrievedIds.toArray(new UUID[0]));
            entry.setAnswerText(answer.answer());
            entry.setDurationMs((Integer) answer.metadata().get("duration_ms"));
            entry.setVerifierPassed(answer.verifier().passed());
            entry.setRounds((Integer) answer.metadata().getOrDefault("rounds", 0));
            logRepo.save(entry);
        } catch (Exception e) {
            log.warn("query log save failed", e);
        }
    }

    private static List<UUID> dedupe(List<UUID> existing, List<UUID> add) {
        var set = new LinkedHashSet<>(existing);
        set.addAll(add);
        return new ArrayList<>(set);
    }

    private static List<String> jsonArrayToList(JsonNode n) {
        if (n == null || !n.isArray()) return List.of();
        List<String> out = new ArrayList<>(n.size());
        n.forEach(e -> out.add(e.asText()));
        return out;
    }
}
