package com.bipros.hds.infrastructure.parser;

import com.bipros.hds.infrastructure.docling.dto.DoclingBlock;
import com.bipros.hds.infrastructure.docling.dto.DoclingResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native PDF text extractor for text-based PDFs (the common HDS case).
 *
 * <p>Why this exists: Docling rasterises every page and runs an ONNX layout model,
 * which needs ~30-50x the file size in RAM. A 350 MB standard blows past 10 GiB and
 * OOM-kills the container. For PDFs whose text is already encoded as text (i.e. not
 * scanned images), we don't need any of that — Apache PDFBox can stream the document
 * page-by-page with bounded memory (~200 MB peak regardless of file size).
 *
 * <p>The extractor emits a {@link DoclingResponse} so the downstream pipeline
 * ({@code ChunkingService}, {@code EmbeddingService}) consumes it identically to a
 * real Docling response. Page numbers on each {@link DoclingBlock} are honest —
 * derived from PDFBox's page index — so citations stay accurate.
 *
 * <p>Heading detection is heuristic: lines beginning with a section number pattern
 * (e.g. "4.3.2 Shoulder Width") are emitted as heading blocks. This works well for
 * standards and specifications, which use rigid numbered hierarchies. Anything else
 * becomes a paragraph block.
 */
@Component("hdsPdfTextExtractor")
@Slf4j
public class PdfTextExtractor {

    /** Matches a section-numbered heading at the start of a line.
     *  "4 Cross Section", "4.3 Shoulder Width", "A.2.1 Appendix Item". */
    private static final Pattern HEADING_PATTERN = Pattern.compile(
        "^\\s*([A-Z]?\\d+(?:\\.\\d+){0,5})\\s+([A-Z][A-Za-z0-9 ,\\-/&'()]{2,120})\\s*$"
    );

    /** A heading line is short — long lines are body even if they start with a number. */
    private static final int MAX_HEADING_LENGTH = 120;

    /**
     * Extracts text from the entire PDF, emitting one DoclingResponse with per-page
     * blocks. The stream is fully consumed; the caller is responsible for closing it
     * (the loader copies bytes to a temp file first to allow random access).
     */
    public DoclingResponse extract(InputStream pdfStream, String fileName) throws IOException {
        File tmp = File.createTempFile("hds-pdfbox-", ".pdf");
        try {
            Files.copy(pdfStream, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return extractFromFile(tmp, fileName);
        } finally {
            if (!tmp.delete()) {
                log.warn("Failed to delete temp PDF file: {}", tmp.getAbsolutePath());
            }
        }
    }

    /**
     * Extracts text from a file on disk. Bounded memory: PDFBox uses scratch files
     * internally (MemoryUsageSetting.setupTempFileOnly) so working set stays small
     * regardless of PDF size.
     */
    public DoclingResponse extractFromFile(File pdfFile, String fileName) throws IOException {
        // Use temp-file-only scratch storage — PDFBox spills internal state to disk
        // instead of holding the whole document tree in memory.
        try (PDDocument doc = PDDocument.load(pdfFile, MemoryUsageSetting.setupTempFileOnly())) {
            int pageCount = doc.getNumberOfPages();
            log.info("PDFBox loaded {} pages from {} ({} bytes on disk)",
                pageCount, fileName, pdfFile.length());

            List<DoclingBlock> blocks = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            // Preserve reading order on multi-column layouts.
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            stripper.setParagraphStart("");
            stripper.setParagraphEnd("");

            for (int pageNum = 1; pageNum <= pageCount; pageNum++) {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String text = stripper.getText(doc);
                if (text == null || text.isBlank()) continue;
                emitBlocksForPage(blocks, text, pageNum);
            }

            DoclingResponse resp = new DoclingResponse();
            resp.setStatus("ok");
            resp.setPages(pageCount);
            resp.setBlocks(blocks);
            log.info("PDFBox extraction complete: pages={}, blocks={}",
                pageCount, blocks.size());
            return resp;
        }
    }

    /**
     * Lightweight probe: opens the PDF, extracts text from a small sample of pages,
     * and reports avg chars-per-page. Used by the router to decide between PDFBox
     * (text-extractable) and Docling (scanned / image-heavy).
     */
    public ExtractabilityProbe probe(File pdfFile, int sampleSize) throws IOException {
        try (PDDocument doc = PDDocument.load(pdfFile, MemoryUsageSetting.setupTempFileOnly())) {
            int pageCount = doc.getNumberOfPages();
            int sampled = Math.min(sampleSize, pageCount);
            if (sampled <= 0) return new ExtractabilityProbe(pageCount, 0, 0);

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            // Spread the sampled pages across the document: first, last, middle thirds.
            int[] pages = chooseSamplePages(pageCount, sampled);
            long totalChars = 0;
            for (int p : pages) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String t = stripper.getText(doc);
                if (t != null) totalChars += t.replaceAll("\\s", "").length();
            }
            int avg = (int) (totalChars / sampled);
            log.info("Extractability probe: pages={}, sampled={}, avg_chars_per_page={}",
                pageCount, sampled, avg);
            return new ExtractabilityProbe(pageCount, sampled, avg);
        }
    }

    private static int[] chooseSamplePages(int pageCount, int sampleSize) {
        if (sampleSize >= pageCount) {
            int[] all = new int[pageCount];
            for (int i = 0; i < pageCount; i++) all[i] = i + 1;
            return all;
        }
        // Always include first and last. Spread the rest evenly between.
        int[] out = new int[sampleSize];
        if (sampleSize == 1) { out[0] = Math.max(1, pageCount / 2); return out; }
        out[0] = 1;
        out[sampleSize - 1] = pageCount;
        for (int i = 1; i < sampleSize - 1; i++) {
            // Even fractional positions: i / (sampleSize - 1) of the way through
            out[i] = Math.max(1, Math.min(pageCount,
                1 + (int) Math.round(((double) i) / (sampleSize - 1) * (pageCount - 1))));
        }
        return out;
    }

    /**
     * Parses one page's raw text into heading + paragraph blocks. Splits on blank
     * lines; lines matching the section-number heading pattern become heading blocks
     * with the detected level set from the dot-count in the number.
     */
    private void emitBlocksForPage(List<DoclingBlock> out, String pageText, int pageNum) {
        String[] lines = pageText.split("\n", -1);
        StringBuilder paragraph = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                flushParagraph(out, paragraph, pageNum);
                continue;
            }

            Matcher m = HEADING_PATTERN.matcher(line);
            if (m.matches() && line.length() <= MAX_HEADING_LENGTH) {
                flushParagraph(out, paragraph, pageNum);
                String sectionNumber = m.group(1);
                String title = m.group(2).strip();
                int level = countLevel(sectionNumber);
                DoclingBlock h = new DoclingBlock();
                h.setType("heading");
                h.setLevel(level);
                h.setText(sectionNumber + " " + title);
                h.setSectionNumber(sectionNumber);
                h.setPage(pageNum);
                out.add(h);
                continue;
            }

            if (paragraph.length() > 0) paragraph.append("\n");
            paragraph.append(line);
        }
        flushParagraph(out, paragraph, pageNum);
    }

    private static void flushParagraph(List<DoclingBlock> out, StringBuilder buf, int pageNum) {
        if (buf.length() == 0) return;
        DoclingBlock p = new DoclingBlock();
        p.setType("paragraph");
        p.setText(buf.toString().strip());
        p.setPage(pageNum);
        out.add(p);
        buf.setLength(0);
    }

    /** Section level = number of dotted components. "4" -> 1, "4.3" -> 2, "4.3.2" -> 3. */
    private static int countLevel(String sectionNumber) {
        int dots = 0;
        for (int i = 0; i < sectionNumber.length(); i++) if (sectionNumber.charAt(i) == '.') dots++;
        return Math.max(1, dots + 1);
    }

    public record ExtractabilityProbe(int pageCount, int sampledPages, int avgCharsPerPage) {}
}
