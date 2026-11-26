package com.fitpilot.rag.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class RagCorpusMigrationIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Test
    void quarantinesBothLegacyEvaluationIdFormatsAndQueuesLiveDocumentsForIndexing() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).target("21").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        UUID run = UUID.randomUUID();
        UUID baseline = seedDocument(jdbc, "eval-v1-baseline-" + run, run);
        UUID experiment = seedDocument(jdbc, "copied-source-ev-" + run + "-bm25", null);
        UUID live = seedDocument(jdbc, "live-source", run);
        jdbc.update("""
                INSERT INTO knowledge_document_revision(id,document_id,version,title,category,source_url,source_license,
                  metadata,trust_level,format,content_hash,raw_content)
                SELECT gen_random_uuid(),id,version,title,category,source_url,source_license,
                  jsonb_build_object('evaluationRunId', ?::text),trust_level,format,content_hash,raw_content
                FROM knowledge_document WHERE id=?
                """, run.toString(), experiment);

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(scope(jdbc, baseline)).isEqualTo("legacy-evaluation");
        assertThat(scope(jdbc, experiment)).isEqualTo("legacy-evaluation");
        assertThat(scope(jdbc, live)).isEqualTo("live");
        assertThat(jdbc.queryForMap("SELECT index_status, index_claim_token, indexed_at FROM knowledge_document WHERE id=?",
                live)).containsEntry("index_status", "PENDING").containsEntry("index_claim_token", null)
                .containsEntry("indexed_at", null);
        assertThat(jdbc.queryForList("SELECT id FROM knowledge_document WHERE corpus_scope='live'", UUID.class))
                .containsExactly(live);
    }

    private UUID seedDocument(JdbcTemplate jdbc, String externalId, UUID legacyRun) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_document(id,external_id,title,category,source_url,source_license,format,
                  content_hash,raw_content,metadata,index_status,index_claim_token,indexed_at)
                VALUES (?,?,'fixture','EVALUATION','https://example.org/fixture','CC-BY','TEXT',?,'fixture',
                  ?::jsonb,'INDEXED',gen_random_uuid(),CURRENT_TIMESTAMP)
                """, id, externalId, "0".repeat(64),
                legacyRun == null ? "{}" : "{\"evaluationRunId\":\"" + legacyRun + "\"}");
        return id;
    }

    private String scope(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT corpus_scope FROM knowledge_document WHERE id=?", String.class, id);
    }
}
