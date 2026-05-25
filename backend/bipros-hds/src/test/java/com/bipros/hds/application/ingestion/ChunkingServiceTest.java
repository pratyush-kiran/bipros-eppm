package com.bipros.hds.application.ingestion;

import com.bipros.hds.domain.enums.HdsChunkType;
import com.bipros.hds.infrastructure.docling.dto.DoclingBlock;
import com.bipros.hds.infrastructure.docling.dto.DoclingResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkingServiceTest {

    @Test
    void splitsAtHeadings() {
        var svc = new ChunkingService();
        var doc = new DoclingResponse();
        doc.setBlocks(List.of(
            blk("heading", 1, 1, "Vol 3", "3"),
            blk("paragraph", null, 1, "Intro about Vol 3.", null),
            blk("heading", 2, 2, "Cross Section", "4"),
            blk("paragraph", null, 2, "Cross section details.", null)
        ));
        List<PreChunk> chunks = svc.chunk(doc);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("Vol 3");
        assertThat(chunks.get(0).content()).contains("Intro");
        assertThat(chunks.get(1).sectionPath()).isEqualTo("Vol 3 > Cross Section");
        assertThat(chunks.get(1).content()).contains("Cross section details");
    }

    @Test
    void tablesAreOwnChunks() {
        var svc = new ChunkingService();
        var doc = new DoclingResponse();
        var t = new DoclingBlock();
        t.setType("table");
        t.setPage(5);
        t.setMarkdown("| a | b |\n| - | - |\n| 1 | 2 |");
        doc.setBlocks(List.of(
            blk("heading", 1, 5, "Tables", "5"),
            t
        ));
        List<PreChunk> chunks = svc.chunk(doc);

        assertThat(chunks).filteredOn(c -> c.chunkType() == HdsChunkType.TABLE)
            .singleElement()
            .satisfies(c -> {
                assertThat(c.content()).contains("Table from Tables").contains("| a | b |");
                assertThat(c.pageStart()).isEqualTo(5);
            });
    }

    @Test
    void splitsLongTextAtTokenCap() {
        var svc = new ChunkingService();
        var doc = new DoclingResponse();
        String big = "lorem ipsum dolor sit amet ".repeat(800);  // ~5000 tokens
        doc.setBlocks(List.of(
            blk("heading", 1, 1, "Big", "1"),
            blk("paragraph", null, 1, big, null)
        ));
        List<PreChunk> chunks = svc.chunk(doc);

        assertThat(chunks.size()).isGreaterThan(3);
        // overlap means consecutive chunks share some tail/head text
        assertThat(chunks.get(1).content()).startsWith(chunks.get(0).content().substring(
            Math.max(0, chunks.get(0).content().length() - 80 * 4)).substring(0, 20));
    }

    private static DoclingBlock blk(String type, Integer level, Integer page, String text, String sec) {
        var b = new DoclingBlock();
        b.setType(type); b.setLevel(level); b.setPage(page); b.setText(text); b.setSectionNumber(sec);
        return b;
    }
}
