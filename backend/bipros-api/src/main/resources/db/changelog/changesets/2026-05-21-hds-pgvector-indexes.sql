--liquibase formatted sql
--changeset bipros:hds-pgvector-hnsw-index
--validCheckSum: ANY
CREATE INDEX IF NOT EXISTS idx_hds_chunk_embedding_hnsw
  ON hds.hds_chunk USING hnsw (embedding vector_cosine_ops);
--rollback DROP INDEX IF EXISTS hds.idx_hds_chunk_embedding_hnsw;

--changeset bipros:hds-tsv-gin-index
CREATE INDEX IF NOT EXISTS idx_hds_chunk_tsv_gin
  ON hds.hds_chunk USING gin (tsv);
--rollback DROP INDEX IF EXISTS hds.idx_hds_chunk_tsv_gin;
