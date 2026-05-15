package com.bipros.ai.insights.project;

import com.bipros.ai.insights.DataHashUtil;
import com.bipros.ai.insights.InsightsCacheService;
import com.bipros.ai.insights.InsightsGenerationLock;
import com.bipros.ai.insights.InsightsGenerator;
import com.bipros.ai.insights.dto.InsightsResponse;
import com.bipros.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/v1/projects/{projectId}/dpr/ai")
@RequiredArgsConstructor
public class DprInsightsController {

    private final DprInsightsCollector collector;
    private final InsightsCacheService cacheService;
    private final InsightsGenerator insightsGenerator;
    private final DataHashUtil dataHashUtil;
    private final InsightsGenerationLock generationLock;

    @PostMapping("/insights")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
    public ResponseEntity<ApiResponse<InsightsResponse>> generate(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "false") boolean force) {
        return generateInsights(projectId, force);
    }

    @PostMapping("/insights/refresh")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
    public ResponseEntity<ApiResponse<InsightsResponse>> refresh(
            @PathVariable UUID projectId) {
        return generateInsights(projectId, true);
    }

    private ResponseEntity<ApiResponse<InsightsResponse>> generateInsights(UUID projectId, boolean force) {
        String tab = collector.tabKey();
        Object lock = generationLock.acquire(projectId, tab);
        try {
            synchronized (lock) {
                if (!force) {
                    JsonNode snapshot = collector.collect(projectId);
                    String hash = dataHashUtil.computeHash(snapshot);
                    Optional<InsightsResponse> cached = cacheService.getCached(projectId, tab, hash);
                    if (cached.isPresent()) {
                        return ResponseEntity.ok(ApiResponse.ok(
                                InsightsGenerator.withCharts(cached.get(), collector.charts(projectId))));
                    }
                }

                JsonNode snapshot = collector.collect(projectId);
                String hash = dataHashUtil.computeHash(snapshot);

                InsightsResponse response = insightsGenerator.generate(tab, snapshot,
                        collector.chartAwarePromptInstructions(), collector.charts(projectId));
                cacheService.save(projectId, tab, hash, response, null, null, null);
                return ResponseEntity.ok(ApiResponse.ok(response));
            }
        } finally {
            generationLock.release(projectId, tab);
        }
    }
}
