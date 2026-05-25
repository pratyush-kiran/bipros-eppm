package com.bipros.hds.application.ingestion;

import com.bipros.hds.domain.enums.HdsChunkType;
import com.bipros.hds.infrastructure.docling.dto.DoclingBlock;
import com.bipros.hds.infrastructure.docling.dto.DoclingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Rules (per spec §5.1):
 *  - Split at heading boundaries — chunks never span sections.
 *  - Token cap 800, overlap 10% (~80 tokens repeated from previous chunk).
 *  - Tables intact (one chunk per table, prefixed with section breadcrumb).
 *  - Figures: caption becomes its own FIGURE_CAPTION chunk.
 *  - section_path is the breadcrumb of all current ancestor headings.
 */
@Service
@Slf4j
public class ChunkingService {

    private static final int TARGET_TOKENS = 800;
    private static final int OVERLAP_TOKENS = 80;

    public List<PreChunk> chunk(DoclingResponse doc) {
        if (doc == null || doc.getBlocks() == null || doc.getBlocks().isEmpty()) return List.of();
        List<PreChunk> out = new ArrayList<>();
        Deque<String> sectionStack = new ArrayDeque<>();   // titles, e.g. ["Vol 3", "4 Cross Section", "4.3 Shoulder Width"]
        String currentSectionNumber = "";
        StringBuilder buffer = new StringBuilder();
        int bufferStartPage = -1;
        int bufferLastPage = -1;
        int chunkIndex = 0;

        for (DoclingBlock b : doc.getBlocks()) {
            String type = b.getType() == null ? "" : b.getType();
            switch (type) {
                case "heading" -> {
                    if (!buffer.isEmpty()) {
                        out.add(emit(chunkIndex++, bufferStartPage, bufferLastPage,
                            joinPath(sectionStack), currentSectionNumber,
                            HdsChunkType.TEXT, buffer.toString()));
                        buffer.setLength(0);
                        bufferStartPage = -1;
                    }
                    int level = b.getLevel() == null ? 1 : b.getLevel();
                    while (sectionStack.size() >= level) sectionStack.pop();
                    sectionStack.push(b.getText() == null ? "" : b.getText().trim());
                    currentSectionNumber = b.getSectionNumber() == null ? currentSectionNumber : b.getSectionNumber();
                }
                case "table" -> {
                    if (!buffer.isEmpty()) {
                        out.add(emit(chunkIndex++, bufferStartPage, bufferLastPage,
                            joinPath(sectionStack), currentSectionNumber, HdsChunkType.TEXT, buffer.toString()));
                        buffer.setLength(0);
                        bufferStartPage = -1;
                    }
                    String md = b.getMarkdown() == null ? "" : b.getMarkdown();
                    String wrapped = "Table from " + joinPath(sectionStack) + "\n\n" + md;
                    out.add(emit(chunkIndex++, page(b), page(b), joinPath(sectionStack),
                        currentSectionNumber, HdsChunkType.TABLE, wrapped));
                }
                case "figure" -> {
                    String caption = b.getText() == null ? "" : b.getText().trim();
                    if (!caption.isEmpty()) {
                        out.add(emit(chunkIndex++, page(b), page(b), joinPath(sectionStack),
                            currentSectionNumber, HdsChunkType.FIGURE_CAPTION, caption));
                    }
                }
                case "list_item" -> appendToBuffer(buffer, b, "- ");
                case "formula" -> {
                    if (!buffer.isEmpty()) {
                        out.add(emit(chunkIndex++, bufferStartPage, bufferLastPage,
                            joinPath(sectionStack), currentSectionNumber, HdsChunkType.TEXT, buffer.toString()));
                        buffer.setLength(0);
                    }
                    out.add(emit(chunkIndex++, page(b), page(b), joinPath(sectionStack),
                        currentSectionNumber, HdsChunkType.FORMULA,
                        b.getText() == null ? "" : b.getText()));
                }
                default -> appendToBuffer(buffer, b, "");
            }

            // page tracking for buffer
            int p = page(b);
            if (p > 0) {
                if (bufferStartPage < 0) bufferStartPage = p;
                bufferLastPage = p;
            }

            // size-based split when buffer is too large — iterate so a very large buffer
            // is sliced into multiple TARGET_TOKENS-sized chunks with OVERLAP_TOKENS carried.
            while (estimateTokens(buffer.toString()) >= TARGET_TOKENS) {
                String text = buffer.toString();
                String head = head(text, TARGET_TOKENS);
                out.add(emit(chunkIndex++, bufferStartPage, bufferLastPage,
                    joinPath(sectionStack), currentSectionNumber, HdsChunkType.TEXT, head));
                // remainder = everything after head, minus head but starting OVERLAP_TOKENS earlier (overlap)
                int headChars = head.length();
                int overlapChars = Math.min(headChars, OVERLAP_TOKENS * 4);
                String remainder = text.substring(headChars - overlapChars);
                buffer.setLength(0);
                buffer.append(remainder);
                bufferStartPage = bufferLastPage;
            }
        }

        if (!buffer.isEmpty()) {
            out.add(emit(chunkIndex, bufferStartPage, bufferLastPage,
                joinPath(sectionStack), currentSectionNumber, HdsChunkType.TEXT, buffer.toString()));
        }
        return out;
    }

    private static void appendToBuffer(StringBuilder buf, DoclingBlock b, String prefix) {
        String t = b.getText() == null ? "" : b.getText().trim();
        if (t.isEmpty()) return;
        if (!buf.isEmpty()) buf.append("\n\n");
        buf.append(prefix).append(t);
    }

    private static int page(DoclingBlock b) {
        return b.getPage() == null ? -1 : b.getPage();
    }

    private static String joinPath(Deque<String> stack) {
        var rev = new ArrayList<>(stack);
        Collections.reverse(rev);
        return String.join(" > ", rev);
    }

    private static int estimateTokens(String s) {
        // Rough: ~4 chars per token for English.
        return s.length() / 4;
    }

    private static String tail(String s, int approxTokens) {
        int chars = Math.min(s.length(), approxTokens * 4);
        return s.substring(s.length() - chars);
    }

    private static String head(String s, int approxTokens) {
        int chars = Math.min(s.length(), approxTokens * 4);
        return s.substring(0, chars);
    }

    private static PreChunk emit(int idx, int pStart, int pEnd, String path, String sectionNo,
                                  HdsChunkType type, String content) {
        int ps = pStart < 0 ? 1 : pStart;
        int pe = pEnd < 0 ? ps : pEnd;
        return new PreChunk(idx, ps, pe, path, sectionNo, type, content, estimateTokens(content));
    }
}
