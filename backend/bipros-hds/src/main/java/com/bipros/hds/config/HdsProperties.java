package com.bipros.hds.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "bipros.hds")
@Data
public class HdsProperties {

    private Storage storage = new Storage();
    private Docling docling = new Docling();
    private Parser parser = new Parser();
    private Embedding embedding = new Embedding();
    private Reranker reranker = new Reranker();
    private Retrieval retrieval = new Retrieval();
    private Verifier verifier = new Verifier();
    private Ingestion ingestion = new Ingestion();

    @Data
    public static class Storage {
        private String bucket;
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String region;
        private int multipartPartSizeMb;
    }

    @Data
    public static class Docling {
        private String url;
        private int timeoutMinutes;
    }

    /**
     * Routing knobs for the PDF parser. Text-extractable PDFs (the common HDS case)
     * go through PDFBox; image/scanned PDFs fall back to Docling.
     */
    @Data
    public static class Parser {
        /** Master switch. False forces all PDFs through Docling (legacy behaviour). */
        private boolean textModeEnabled = true;
        /** Threshold: if PDFBox extracts at least this many chars/page on average, treat the
         *  PDF as text-based and skip Docling entirely. */
        private int minCharsPerPage = 200;
        /** How many pages to sample for the text-extractability probe. Sampled pages are
         *  spread across the document (first, last, middle thirds). */
        private int probeSampleSize = 5;
        /** Hard fallback ceiling: regardless of probe outcome, any PDF larger than this
         *  forces the PDFBox path because Docling can't safely handle it on the dev VM.
         *  Set 0 to disable. */
        private int forcePdfBoxOverMb = 100;
    }

    @Data
    public static class Embedding {
        private String model;
        private int dimensions;
        private int batchSize;
        private int concurrency;
    }

    @Data
    public static class Reranker {
        private boolean enabled;
        private String url;
        private int topK;
    }

    @Data
    public static class Retrieval {
        private double similarityFloor;
        private int bm25TopK;
        private int vectorTopK;
        private int maxChunksPerQuery;
        private int maxRounds;
        private int cacheTtlSeconds;
    }

    @Data
    public static class Verifier {
        private int maxRetries;
    }

    @Data
    public static class Ingestion {
        private int workerPollSeconds;
        private int heartbeatIntervalSeconds;
        private int staleJobAfterSeconds;
    }
}
