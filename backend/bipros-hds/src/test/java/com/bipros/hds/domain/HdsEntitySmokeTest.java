package com.bipros.hds.domain;

import com.bipros.hds.domain.enums.HdsDiscipline;
import com.bipros.hds.domain.enums.HdsVersionStatus;
import com.bipros.hds.domain.repo.HdsDocumentRepository;
import com.bipros.hds.domain.repo.HdsVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(HdsEntitySmokeTest.TestApp.class)
@Testcontainers
@TestPropertySource(properties = {
    "spring.jpa.properties.hibernate.default_schema=hds",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class HdsEntitySmokeTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test")
        .withInitScript("init-hds-test-schema.sql");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired HdsDocumentRepository docRepo;
    @Autowired HdsVersionRepository verRepo;

    @Test
    void persistsDocumentAndVersion() {
        var doc = HdsDocument.builder()
            .title("HDS Vol 3")
            .shortCode("HDS-V3")
            .discipline(HdsDiscipline.HIGHWAY)
            .build();
        doc = docRepo.save(doc);

        var ver = HdsVersion.builder()
            .hdsDocumentId(doc.getId())
            .versionLabel("Rev 2.1")
            .revisionYear(2024)
            .status(HdsVersionStatus.PENDING)
            .fileSha256("a".repeat(64))
            .build();
        ver = verRepo.save(ver);

        assertThat(docRepo.findByShortCode("HDS-V3")).isPresent();
        assertThat(verRepo.findByHdsDocumentIdOrderByRevisionYearDesc(doc.getId()))
            .singleElement()
            .satisfies(v -> assertThat(v.getStatus()).isEqualTo(HdsVersionStatus.PENDING));
    }
}
