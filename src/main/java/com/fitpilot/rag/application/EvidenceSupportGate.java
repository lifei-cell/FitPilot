package com.fitpilot.rag.application;

import com.fitpilot.rag.processing.TextAnalyzer;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conservative, deterministic guard before citations leave the retrieval layer.
 * It checks whether the passage contains the query's concrete anchors and refuses
 * requests for facts that are absent from the passage. It is intentionally not an
 * answer generator or a general natural-language entailment model.
 */
public final class EvidenceSupportGate {
    private static final Pattern NUMBER = Pattern.compile(
            "(?:\\d+(?:[.]\\d+)?|[零一二三四五六七八九十百千万两]+)");
    private static final Set<String> QUESTION_TOKENS = Set.of(
            "请", "请问", "如何", "怎么", "怎样", "什么", "是否", "能否", "能不能", "可以", "应该",
            "需要", "当前", "目前", "哪个", "哪种", "哪位", "多少", "几", "第几", "吗", "呢", "的",
            "与", "和", "及", "以及", "这", "这个", "那", "在", "对", "从", "到", "中", "时", "后", "前");

    /* These phrases change the requested fact. They are not treated as supported
       merely because the passage discusses the same exercise or topic. */
    private static final List<String> SPECIFIC_REQUESTS = List.of(
            "世界纪录", "赛事规则", "正式赛事", "比赛", "规则", "治愈", "肩峰撞击", "伤膝", "刺痛",
            "止痛药", "类固醇", "手术", "撕裂", "药物", "服用", "剂量", "诊断", "确诊", "失眠症",
            "睡眠呼吸暂停", "相互作用", "品牌", "保修", "价格", "最便宜", "比较", "更高", "更快",
            "最准确", "最早", "哪一年", "谁", "精确", "准确", "厘米", "公斤", "千卡", "毫克",
            "焦耳", "螺丝", "膨胀", "清洗", "溶液", "门框", "固定", "换算", "血乳酸", "心率",
            "周期", "有效组", "百分之", "数值", "阈值", "四十八小时");

    private static final Set<String> UNITS = Set.of(
            "次", "组", "公斤", "千克", "克", "毫克", "秒", "分钟", "小时", "厘米", "米", "千卡", "%");

    private final TextAnalyzer analyzer;
    private final double minimumCoverage;
    private final boolean enabled;

    public EvidenceSupportGate(TextAnalyzer analyzer, boolean enabled, double minimumCoverage) {
        this.analyzer = analyzer;
        this.enabled = enabled;
        this.minimumCoverage = Math.max(0, Math.min(1, minimumCoverage));
    }

    public Decision verify(String query, String passage) {
        if (!enabled) return Decision.supported("DISABLED", 1, List.of());
        String normalizedQuery = normalize(query);
        String normalizedPassage = normalize(passage);
        if (normalizedQuery.isBlank() || normalizedPassage.isBlank()) {
            return Decision.abstained("EMPTY_QUERY_OR_PASSAGE", 0, List.of());
        }
        String specificGap = specificGap(normalizedQuery, normalizedPassage);
        if (specificGap != null) {
            return Decision.abstained("UNSUPPORTED_SPECIFIC_REQUEST", 0, List.of(specificGap));
        }

        List<String> anchors = anchors(query);
        if (anchors.isEmpty()) return Decision.abstained("NO_QUERY_ANCHOR", 0, List.of());
        List<String> missing = anchors.stream().filter(anchor -> !normalizedPassage.contains(anchor)).toList();
        int matched = anchors.size() - missing.size();
        double coverage = (double) matched / anchors.size();
        int minimumMatched = anchors.size() <= 2 ? 1 : 2;
        if (matched < minimumMatched || coverage < minimumCoverage) {
            return Decision.abstained("LOW_QUERY_TERM_COVERAGE", coverage, missing);
        }
        return Decision.supported("EVIDENCE_FOUND", coverage, missing);
    }

    private List<String> anchors(String query) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String token : analyzer.tokens(query)) {
            String normalized = normalize(token);
            if (normalized.isBlank() || QUESTION_TOKENS.contains(normalized)
                    || normalized.chars().allMatch(Character::isDigit)
                    || normalized.length() == 1 && QUESTION_TOKENS.contains(normalized)
                    || normalized.contains("多少") || normalized.contains("第几")) continue;
            if (normalized.codePointCount(0, normalized.length()) >= 2 || normalized.matches("[a-z0-9.-]+")) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    private String specificGap(String query, String passage) {
        for (String request : SPECIFIC_REQUESTS) {
            if (query.contains(request) && !passage.contains(normalize(request))) {
                if (request.equals("准确") || request.equals("精确") || request.equals("数值")
                        || request.equals("阈值") || request.equals("多少")) {
                    continue;
                }
                return request;
            }
        }
        if (containsNumericQuestion(query) && !hasNumericAnswer(query, passage)) return "NUMERIC_VALUE";
        return null;
    }

    private boolean containsNumericQuestion(String query) {
        return query.contains("多少") || query.contains("几") || query.contains("第几")
                || query.contains("百分之") || query.contains("什么数值");
    }

    private boolean hasNumericAnswer(String query, String passage) {
        if (!NUMBER.matcher(passage).find()) return false;
        return UNITS.stream().filter(query::contains).anyMatch(passage::contains)
                || query.contains("多少") && passage.chars().anyMatch(Character::isDigit);
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "").trim();
    }

    public record Decision(boolean supported, String reason, double coverage, List<String> missingAnchors) {
        private static Decision supported(String reason, double coverage, List<String> missing) {
            return new Decision(true, reason, coverage, List.copyOf(missing));
        }

        private static Decision abstained(String reason, double coverage, List<String> missing) {
            return new Decision(false, reason, coverage, List.copyOf(missing));
        }
    }
}
