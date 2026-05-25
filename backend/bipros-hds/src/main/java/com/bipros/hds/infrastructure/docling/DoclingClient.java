package com.bipros.hds.infrastructure.docling;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.infrastructure.docling.dto.DoclingBlock;
import com.bipros.hds.infrastructure.docling.dto.DoclingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Calls docling-serve's /v1/convert/file endpoint and converts the markdown content
 * it returns into the synthetic DoclingBlock list the chunker consumes.
 *
 * <p>For large PDFs (hundreds of MB) the body is streamed via {@link InputStreamResource}
 * instead of buffered into a byte array. The underlying Reactor Netty connector is
 * configured with multi-hour timeouts so Docling has enough wall time to parse 1 GB docs.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DoclingClient {

    private static final int CODEC_MAX_BYTES = 2 * 1024 * 1024 * 1024 - 1;   // ~2 GB

    private final HdsProperties props;
    private WebClient webClient;

    private WebClient client() {
        if (webClient == null) {
            int timeoutMinutes = Math.max(15, props.getDocling().getTimeoutMinutes());
            HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 60_000)
                .responseTimeout(Duration.ofMinutes(timeoutMinutes))
                .doOnConnected(conn -> conn
                    .addHandlerLast(new ReadTimeoutHandler(timeoutMinutes * 60L, TimeUnit.SECONDS))
                    .addHandlerLast(new WriteTimeoutHandler(timeoutMinutes * 60L, TimeUnit.SECONDS)));

            webClient = WebClient.builder()
                .baseUrl(props.getDocling().getUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(CODEC_MAX_BYTES))
                .build();
        }
        return webClient;
    }

    /**
     * Streamed variant — preferred for large PDFs.
     * The caller is responsible for closing the stream after the call returns.
     */
    public DoclingResponse parse(InputStream pdfStream, long contentLength, String fileName) {
        log.info("Submitting PDF to Docling (stream): name={}, length={} bytes", fileName, contentLength);
        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        // InputStreamResource with explicit content length streams the body
        // instead of buffering it.
        InputStreamResource resource = new InputStreamResource(pdfStream) {
            @Override public String getFilename() { return fileName; }
            @Override public long contentLength() { return contentLength; }
        };
        mb.part("files", resource).contentType(MediaType.APPLICATION_PDF);
        mb.part("to_formats", "md");
        mb.part("do_ocr", "false");
        mb.part("do_table_structure", "true");

        Duration timeout = Duration.ofMinutes(Math.max(15, props.getDocling().getTimeoutMinutes()));
        JsonNode raw = client().post()
            .uri("/v1/convert/file")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(mb.build()))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block(timeout);

        if (raw == null) {
            throw new IllegalStateException("Docling returned null response");
        }
        String status = raw.path("status").asText("ok");
        String md = raw.path("document").path("md_content").asText("");
        if (md.isBlank()) {
            log.warn("Docling returned empty md_content; status={}", status);
        }

        DoclingResponse resp = new DoclingResponse();
        resp.setStatus(status);
        resp.setBlocks(markdownToBlocks(md));
        resp.setPages(1);
        log.info("Docling parse complete: status={}, blocks={}", resp.getStatus(), resp.getBlocks().size());
        return resp;
    }

    /**
     * Buffered variant kept for tests and small payloads.
     */
    public DoclingResponse parse(byte[] pdfBytes, String fileName) {
        return parse(new java.io.ByteArrayInputStream(pdfBytes), pdfBytes.length, fileName);
    }

    /**
     * Converts a markdown document into a flat list of DoclingBlock entries.
     *  - Lines starting with `#` become heading blocks (level = number of `#`).
     *  - Other non-blank line groups become paragraph blocks.
     *  - Markdown tables (`| ... |`) become a single table block (a contiguous run).
     */
    private List<DoclingBlock> markdownToBlocks(String md) {
        List<DoclingBlock> out = new ArrayList<>();
        if (md == null || md.isBlank()) return out;
        String[] lines = md.split("\n", -1);

        StringBuilder paragraph = new StringBuilder();
        StringBuilder table = new StringBuilder();
        boolean inTable = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                flushParagraph(paragraph, out);
                if (!inTable) inTable = true;
                table.append(line).append("\n");
                continue;
            } else if (inTable) {
                DoclingBlock tb = new DoclingBlock();
                tb.setType("table");
                tb.setMarkdown(table.toString().trim());
                tb.setPage(1);
                out.add(tb);
                table.setLength(0);
                inTable = false;
            }

            if (trimmed.startsWith("#")) {
                flushParagraph(paragraph, out);
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                String headingText = trimmed.substring(level).trim();
                DoclingBlock hb = new DoclingBlock();
                hb.setType("heading");
                hb.setLevel(level);
                hb.setText(headingText);
                hb.setPage(1);
                hb.setSectionNumber(leadingSectionNumber(headingText));
                out.add(hb);
                continue;
            }

            if (trimmed.isEmpty()) {
                flushParagraph(paragraph, out);
            } else {
                if (paragraph.length() > 0) paragraph.append("\n");
                paragraph.append(line);
            }
        }
        if (inTable) {
            DoclingBlock tb = new DoclingBlock();
            tb.setType("table");
            tb.setMarkdown(table.toString().trim());
            tb.setPage(1);
            out.add(tb);
        }
        flushParagraph(paragraph, out);
        return out;
    }

    private void flushParagraph(StringBuilder paragraph, List<DoclingBlock> out) {
        if (paragraph.length() == 0) return;
        DoclingBlock pb = new DoclingBlock();
        pb.setType("paragraph");
        pb.setText(paragraph.toString().trim());
        pb.setPage(1);
        out.add(pb);
        paragraph.setLength(0);
    }

    private String leadingSectionNumber(String heading) {
        int i = 0;
        while (i < heading.length() && (Character.isDigit(heading.charAt(i)) || heading.charAt(i) == '.')) {
            i++;
        }
        if (i == 0) return null;
        String prefix = heading.substring(0, i).replaceAll("\\.$", "");
        return prefix.isEmpty() ? null : prefix;
    }
}
