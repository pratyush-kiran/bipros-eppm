package com.bipros.hds.infrastructure.retrieval;

import com.bipros.hds.domain.enums.HdsChunkType;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@Slf4j
@RequiredArgsConstructor
public class HybridSearchRepository {

    private final JdbcTemplate jdbc;

    /** Top-K chunks by cosine similarity within selected versions, filtered by floor. */
    public List<UUID> searchByEmbedding(float[] query, List<UUID> selectedVersionIds,
                                        double similarityFloor, int topK) {
        if (selectedVersionIds.isEmpty()) return List.of();
        PGvector q = new PGvector(query);

        return jdbc.query(con -> {
            Array versions = con.createArrayOf("uuid", selectedVersionIds.toArray());
            var ps = con.prepareStatement(
                "SELECT id FROM hds.hds_chunk " +
                "WHERE hds_version_id = ANY(?) " +
                "  AND 1 - (embedding <=> ?) >= ? " +
                "ORDER BY embedding <=> ? " +
                "LIMIT ?");
            ps.setArray(1, versions);
            ps.setObject(2, q);
            ps.setDouble(3, similarityFloor);
            ps.setObject(4, q);
            ps.setInt(5, topK);
            return ps;
        }, (rs, n) -> (UUID) rs.getObject("id"));
    }

    /** Top-K chunks by BM25-ish keyword score within selected versions. */
    public List<UUID> searchByKeyword(String query, List<UUID> selectedVersionIds, int topK) {
        if (selectedVersionIds.isEmpty()) return List.of();
        return jdbc.query(con -> {
            Array versions = con.createArrayOf("uuid", selectedVersionIds.toArray());
            var ps = con.prepareStatement(
                "SELECT id FROM hds.hds_chunk " +
                "WHERE hds_version_id = ANY(?) " +
                "  AND tsv @@ plainto_tsquery('english', ?) " +
                "ORDER BY ts_rank(tsv, plainto_tsquery('english', ?)) DESC " +
                "LIMIT ?");
            ps.setArray(1, versions);
            ps.setString(2, query);
            ps.setString(3, query);
            ps.setInt(4, topK);
            return ps;
        }, (rs, n) -> (UUID) rs.getObject("id"));
    }

    /**
     * Structural sample for overview-intent questions. Returns up to {@code perVersionLimit}
     * chunks from each selected version ordered by chunk_index — typically the table of
     * contents, intro, and first few section starts. No vector search; no similarity floor.
     * Result is ordered by version then chunk_index so the answer reads as a coherent map
     * of each document in turn.
     */
    public List<UUID> sampleOverviewChunks(List<UUID> selectedVersionIds, int perVersionLimit) {
        if (selectedVersionIds.isEmpty() || perVersionLimit <= 0) return List.of();
        return jdbc.query(con -> {
            Array versions = con.createArrayOf("uuid", selectedVersionIds.toArray());
            // ROW_NUMBER per version, take the first N — keeps the result short even
            // when the corpus is large.
            var ps = con.prepareStatement(
                "WITH ranked AS (" +
                "  SELECT id, hds_version_id, chunk_index, " +
                "         ROW_NUMBER() OVER (PARTITION BY hds_version_id ORDER BY chunk_index) AS rn " +
                "  FROM hds.hds_chunk " +
                "  WHERE hds_version_id = ANY(?)" +
                ") " +
                "SELECT id FROM ranked WHERE rn <= ? ORDER BY hds_version_id, chunk_index");
            ps.setArray(1, versions);
            ps.setInt(2, perVersionLimit);
            return ps;
        }, (rs, n) -> (UUID) rs.getObject("id"));
    }

    /** Fetch chunks by IDs preserving the given order. */
    public List<ChunkRow> fetchChunks(List<UUID> chunkIds) {
        if (chunkIds.isEmpty()) return List.of();
        // Use ANY then re-order in Java.
        Object[] ids = chunkIds.toArray();
        List<ChunkRow> rows = jdbc.query(con -> {
            Array a = con.createArrayOf("uuid", ids);
            var ps = con.prepareStatement(
                "SELECT id, hds_version_id, chunk_index, page_start, page_end, section_path, section_number, " +
                "       chunk_type, content, content_tokens " +
                "FROM hds.hds_chunk WHERE id = ANY(?)");
            ps.setArray(1, a);
            return ps;
        }, (rs, n) -> new ChunkRow(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("hds_version_id"),
            rs.getInt("chunk_index"),
            rs.getInt("page_start"),
            rs.getInt("page_end"),
            rs.getString("section_path"),
            rs.getString("section_number"),
            HdsChunkType.valueOf(rs.getString("chunk_type")),
            rs.getString("content"),
            (Integer) rs.getObject("content_tokens")));
        // Re-order to match input
        var byId = new java.util.HashMap<UUID, ChunkRow>(rows.size());
        rows.forEach(r -> byId.put(r.id(), r));
        var out = new ArrayList<ChunkRow>(chunkIds.size());
        for (UUID id : chunkIds) {
            var c = byId.get(id);
            if (c != null) out.add(c);
        }
        return out;
    }

    /**
     * Bulk insert chunks with embeddings using a JDBC batch.
     * `embeddings.size()` must equal `chunks.size()`.
     */
    public int[] insertChunks(List<ChunkInsert> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunks/embeddings size mismatch");
        }
        return jdbc.batchUpdate(
            "INSERT INTO hds.hds_chunk " +
            "(id, hds_version_id, chunk_index, page_start, page_end, section_path, section_number, " +
            " chunk_type, content, content_tokens, embedding, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
            new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                @Override public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                    var c = chunks.get(i);
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, c.hdsVersionId());
                    ps.setInt(3, c.chunkIndex());
                    ps.setInt(4, c.pageStart());
                    ps.setInt(5, c.pageEnd());
                    ps.setString(6, c.sectionPath());
                    ps.setString(7, c.sectionNumber());
                    ps.setString(8, c.chunkType().name());
                    ps.setString(9, c.content());
                    if (c.contentTokens() == null) ps.setNull(10, java.sql.Types.INTEGER);
                    else ps.setInt(10, c.contentTokens());
                    ps.setObject(11, new PGvector(embeddings.get(i)));
                }
                @Override public int getBatchSize() { return chunks.size(); }
            });
    }

    public record ChunkRow(UUID id, UUID hdsVersionId, int chunkIndex, int pageStart, int pageEnd,
                           String sectionPath, String sectionNumber, HdsChunkType chunkType,
                           String content, Integer contentTokens) {}

    public record ChunkInsert(UUID hdsVersionId, int chunkIndex, int pageStart, int pageEnd,
                              String sectionPath, String sectionNumber, HdsChunkType chunkType,
                              String content, Integer contentTokens) {}
}
