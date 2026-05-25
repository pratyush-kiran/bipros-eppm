package com.bipros.bootstrap.input;

import com.bipros.bootstrap.model.ParsedDataset;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Loads the static {@code bootstrap-data.json} fixture from the classpath.
 *
 * <p>That fixture is generated build-time by {@code scripts/extract.py} and
 * committed to the repo. Every stage reads the same object — no LLM, no
 * network, no API key. Re-extract by running the Python script when the
 * source workbook changes.
 */
@Component
@Slf4j
public class ParsedDatasetStore {

    private static final String RESOURCE_PATH = "bootstrap-data.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ParsedDataset cached;

    public synchronized ParsedDataset load() {
        if (cached != null) return cached;
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "bootstrap-data.json not found on classpath. " +
                    "Run scripts/extract.py to (re)generate it under " +
                    "bipros-data-bootstrap/src/main/resources/.");
        }
        try (InputStream in = resource.getInputStream()) {
            cached = mapper.readValue(in, ParsedDataset.class);
            log.info("Loaded bootstrap-data.json from classpath ({} bytes)", resource.contentLength());
            return cached;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read bootstrap-data.json from classpath", e);
        }
    }
}
