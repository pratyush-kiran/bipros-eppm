package com.bipros.hds.infrastructure.parser;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.infrastructure.docling.DoclingClient;
import com.bipros.hds.infrastructure.docling.dto.DoclingResponse;
import com.bipros.hds.infrastructure.parser.PdfTextExtractor.ExtractabilityProbe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Decides whether a PDF should be parsed by PDFBox (cheap, text-only) or sent to
 * Docling (rich layout + table structure, expensive). The decision logic is:
 *
 * <ol>
 *   <li>If text-mode is disabled, always use Docling.</li>
 *   <li>Spool the stream to a temp file (we need random access regardless of path).</li>
 *   <li>If file size exceeds {@code forcePdfBoxOverMb}, take the PDFBox path
 *       unconditionally — Docling can't safely handle the file on this VM.</li>
 *   <li>Probe extractability: sample N pages, measure avg chars/page.</li>
 *   <li>If avg ≥ {@code minCharsPerPage}, use PDFBox (the PDF is text-based).</li>
 *   <li>Otherwise fall back to Docling (the PDF is image-heavy / scanned).</li>
 * </ol>
 *
 * The PDFBox path stays under ~200 MB peak memory regardless of input size.
 * Docling can still OOM on very large image PDFs — for those the router only sends
 * inputs that fit Docling's effective limits.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RoutingPdfParser {

    private final HdsProperties props;
    private final PdfTextExtractor textExtractor;
    private final DoclingClient docling;

    /**
     * Parse a PDF stream. The caller owns the stream; this method reads it to
     * completion (spooling to a temp file) before returning. The temp file is
     * always cleaned up.
     */
    public DoclingResponse parse(InputStream pdfStream, long contentLength, String fileName)
            throws IOException {
        HdsProperties.Parser cfg = props.getParser();

        // Short-circuit: text mode disabled → legacy Docling-everywhere behaviour.
        if (!cfg.isTextModeEnabled()) {
            log.info("Parser routing: textModeEnabled=false → Docling (file={}, size={} B)",
                fileName, contentLength);
            return docling.parse(pdfStream, contentLength, fileName);
        }

        File tmp = File.createTempFile("hds-router-", ".pdf");
        try {
            Files.copy(pdfStream, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            long sizeBytes = tmp.length();
            long sizeMb = sizeBytes / (1024 * 1024);

            // Hard ceiling: oversized PDFs always go to PDFBox.
            if (cfg.getForcePdfBoxOverMb() > 0 && sizeMb >= cfg.getForcePdfBoxOverMb()) {
                log.info("Parser routing: size={} MB ≥ forcePdfBoxOverMb={} MB → PDFBox (file={})",
                    sizeMb, cfg.getForcePdfBoxOverMb(), fileName);
                return textExtractor.extractFromFile(tmp, fileName);
            }

            // Probe extractability on a small page sample.
            ExtractabilityProbe probe;
            try {
                probe = textExtractor.probe(tmp, cfg.getProbeSampleSize());
            } catch (Exception probeFail) {
                // If we can't even probe the PDF with PDFBox (corrupt, encrypted, exotic),
                // fall back to Docling — it sometimes handles edge cases PDFBox doesn't.
                log.warn("Extractability probe failed for {}: {} — falling back to Docling",
                    fileName, probeFail.getMessage());
                try (InputStream re = new BufferedInputStream(new FileInputStream(tmp))) {
                    return docling.parse(re, sizeBytes, fileName);
                }
            }

            if (probe.avgCharsPerPage() >= cfg.getMinCharsPerPage()) {
                log.info("Parser routing: avg_chars={} ≥ min={} → PDFBox (file={}, pages={})",
                    probe.avgCharsPerPage(), cfg.getMinCharsPerPage(), fileName, probe.pageCount());
                return textExtractor.extractFromFile(tmp, fileName);
            }

            log.info("Parser routing: avg_chars={} < min={} → Docling (file={}, pages={}, size={} MB)",
                probe.avgCharsPerPage(), cfg.getMinCharsPerPage(),
                fileName, probe.pageCount(), sizeMb);
            try (InputStream re = new BufferedInputStream(new FileInputStream(tmp))) {
                return docling.parse(re, sizeBytes, fileName);
            }
        } finally {
            if (!tmp.delete()) {
                log.warn("Failed to delete router temp file: {}", tmp.getAbsolutePath());
            }
        }
    }
}
