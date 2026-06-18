package com.bipros.admin.application.service;

import com.bipros.admin.application.dto.BrandingDto;
import com.bipros.admin.domain.model.GlobalSetting;
import com.bipros.admin.domain.repository.GlobalSettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves branding for the globally-active theme. Reads the existing
 * {@code ui.active_theme} and {@code ui.custom_themes} settings directly via the
 * repository (Optional-based) so a missing key is a normal "use defaults" path,
 * not an exception.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BrandingService {

    private static final String KEY_ACTIVE = "ui.active_theme";
    private static final String KEY_CUSTOM = "ui.custom_themes";
    private static final String DEFAULT_PRIMARY = "Bipros";
    private static final String DEFAULT_SECONDARY = "EPPM";

    private final GlobalSettingRepository globalSettingRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public BrandingDto resolveActive() {
        String activeId = globalSettingRepository.findBySettingKey(KEY_ACTIVE)
                .map(GlobalSetting::getSettingValue)
                .orElse(null);
        if (activeId == null || activeId.isBlank()) {
            return defaults();
        }

        String customJson = globalSettingRepository.findBySettingKey(KEY_CUSTOM)
                .map(GlobalSetting::getSettingValue)
                .orElse(null);
        if (customJson == null || customJson.isBlank()) {
            return defaults();
        }

        try {
            JsonNode arr = objectMapper.readTree(customJson);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    if (activeId.equals(node.path("id").asText(null))) {
                        return new BrandingDto(
                                textOrNull(node, "logoLight"),
                                textOrNull(node, "logoDark"),
                                textOrDefault(node, "appNamePrimary", DEFAULT_PRIMARY),
                                textOrDefault(node, "appNameSecondary", DEFAULT_SECONDARY));
                    }
                }
            }
        } catch (Exception e) {
            // Malformed ui.custom_themes JSON — fall through to defaults, never 500.
            log.warn("Failed to parse ui.custom_themes for branding; using defaults", e);
        }
        return defaults();
    }

    private static BrandingDto defaults() {
        return new BrandingDto(null, null, DEFAULT_PRIMARY, DEFAULT_SECONDARY);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private static String textOrDefault(JsonNode node, String field, String def) {
        String v = textOrNull(node, field);
        return v == null ? def : v;
    }
}
