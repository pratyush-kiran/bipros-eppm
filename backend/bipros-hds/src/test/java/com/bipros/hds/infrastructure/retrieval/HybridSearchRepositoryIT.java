package com.bipros.hds.infrastructure.retrieval;

import com.bipros.hds.domain.enums.HdsChunkType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({HybridSearchRepository.class, HybridSearchRepositoryIT.TestApp.class})
@Testcontainers
class HybridSearchRepositoryIT {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}


    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
        .withDatabaseName("test").withUsername("test").withPassword("test")
        .withInitScript("init-hds-test-schema.sql");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired HybridSearchRepository repo;

    @Test
    void roundTripsInsertAndKeywordSearch() {
        jdbc.execute("""
          CREATE TABLE IF NOT EXISTS hds.hds_chunk (
            id uuid primary key,
            hds_version_id uuid not null,
            chunk_index int not null,
            page_start int not null,
            page_end int not null,
            section_path text not null,
            section_number varchar(32),
            chunk_type varchar(16) not null,
            content text not null,
            content_tokens int,
            embedding vector(3) not null,
            tsv tsvector generated always as (to_tsvector('english', content)) stored,
            created_at timestamptz default now(),
            updated_at timestamptz default now()
          );
          CREATE INDEX IF NOT EXISTS idx_test_hds_chunk_tsv ON hds.hds_chunk USING gin(tsv);
          """);

        UUID v = UUID.randomUUID();
        repo.insertChunks(
            List.of(
                new HybridSearchRepository.ChunkInsert(v, 0, 1, 1, "S>1", "1",
                    HdsChunkType.TEXT, "shoulder width specification", 4),
                new HybridSearchRepository.ChunkInsert(v, 1, 2, 2, "S>2", "2",
                    HdsChunkType.TEXT, "lane width minimum 3.0m", 4)
            ),
            List.of(new float[]{0.1f, 0.2f, 0.3f}, new float[]{0.4f, 0.5f, 0.6f}));

        List<UUID> hits = repo.searchByKeyword("shoulder", List.of(v), 10);
        assertThat(hits).hasSize(1);

        var rows = repo.fetchChunks(hits);
        assertThat(rows).singleElement()
            .satisfies(r -> assertThat(r.content()).contains("shoulder"));
    }
}
