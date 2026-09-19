package com.fitpilot.rag.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fitpilot.rag.processing.TextAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceSupportGateTest {
    private static final String HARD_NEGATIVES_V2_SHA256 =
            "AB48E6BC7B894FE950C0F7BB17CD3B2906E6757F99134249E14FD99FDEFB44DC";

    private final EvidenceSupportGate gate = new EvidenceSupportGate(new TextAnalyzer(), true, .4);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rejectsEveryQueryInTheIndependentHardNegativeV2Set() throws Exception {
        byte[] raw = resource("/retrieval/hard-negatives-v2.json").readAllBytes();
        assertThat(sha256(raw)).isEqualTo(HARD_NEGATIVES_V2_SHA256);
        List<HardNegative> cases = json.readValue(raw, new TypeReference<>() {});

        List<EvidenceSupportGate.Decision> decisions = cases.stream()
                .map(item -> gate.verify(item.query(), passage(item.topic())))
                .toList();

        assertThat(cases).hasSize(20);
        assertThat(decisions).allMatch(decision -> !decision.supported());
        assertThat(decisions).extracting(EvidenceSupportGate.Decision::reason)
                .allMatch(reason -> List.of("UNSUPPORTED_SPECIFIC_REQUEST", "LOW_QUERY_TERM_COVERAGE",
                        "NUMERIC_VALUE").contains(reason));
    }

    @Test
    void keepsTheAnswerableEvaluationQueriesSupported() throws Exception {
        List<AnswerCase> cases = readAnswerCases();
        Map<String, String> passages = corpus();

        assertThat(cases).hasSize(50);
        assertThat(cases).allMatch(item -> gate.verify(item.query(), passages.get(item.source())).supported());
    }

    @Test
    void acceptsNumericAnswerWhenThePassageContainsTheValueAndUnit() {
        var decision = gate.verify("RPE 8 还剩多少次重复",
                "RPE 8 通常表示这一组结束时还保留大约两次重复。");

        assertThat(decision.supported()).isTrue();
    }

    private List<AnswerCase> readAnswerCases() throws Exception {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                resource("/eval/rag-v1.jsonl"), StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank())
                    .map(line -> read(line, AnswerCase.class)).toList();
        }
    }

    private Map<String, String> corpus() throws Exception {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                resource("/eval/rag-corpus-v1.jsonl"), StandardCharsets.UTF_8))) {
            Map<String, String> result = new LinkedHashMap<>();
            reader.lines().filter(line -> !line.isBlank()).forEach(line -> {
                CorpusDocument document = read(line, CorpusDocument.class);
                String source = document.sourceUrl().substring(document.sourceUrl().lastIndexOf('/') + 1);
                result.put(source, document.title() + " " + document.content());
            });
            return result;
        }
    }

    private String passage(String topic) {
        try {
            return corpus().get(topic.equals("training-volume") ? "training-volume"
                    : topic.equals("recovery-sleep") ? "recovery-sleep" : topic);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private InputStream resource(String path) {
        InputStream input = getClass().getResourceAsStream(path);
        if (input == null) throw new IllegalStateException("missing test resource " + path);
        return input;
    }

    private String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)).toUpperCase();
    }

    record HardNegative(String id, String topic, String query, String missingFact) {}
    record AnswerCase(String id, String query, List<String> expectedTerms, List<String> expectedSourceUrls, String category) {
        String source() { return expectedSourceUrls.getFirst().substring(expectedSourceUrls.getFirst().lastIndexOf('/') + 1); }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CorpusDocument(String externalId, String title, String category, String sourceUrl, String content) {}
}
