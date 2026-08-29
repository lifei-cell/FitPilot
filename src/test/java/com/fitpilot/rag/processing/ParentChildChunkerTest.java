package com.fitpilot.rag.processing;

import com.fitpilot.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParentChildChunkerTest {
    @Test
    void parsesMarkdownAndReturnsSmallerChildrenWithStableOrdinals() {
        RagProperties properties = new RagProperties();
        properties.getChunking().setParentMaxChars(180);
        properties.getChunking().setChildMaxChars(50);
        properties.getChunking().setChildOverlapChars(15);
        DocumentParser parser = new DocumentParser();
        ParentChildChunker chunker = new ParentChildChunker(properties);

        var sections = parser.parse("MARKDOWN", "Guide", """
                # RPE
                RPE 8 通常表示还保留大约两次重复。训练者应保持动作技术稳定，并根据当天状态调整负重。

                第二段用于验证重叠切分，同时保证检索命中后能够返回完整父上下文。
                # Deload
                减量周通过降低训练容量或强度来管理疲劳。
                """);
        var chunks = chunker.chunk(sections);

        assertThat(chunks).extracting(ParentChildChunker.ParentChunk::heading)
                .contains("RPE", "Deload");
        assertThat(chunks.stream().flatMap(parent -> parent.children().stream()).toList())
                .hasSizeGreaterThan(2)
                .allSatisfy(child -> assertThat(child.content().length()).isLessThanOrEqualTo(50));
        var ordinals = chunks.stream().flatMap(parent -> parent.children().stream())
                .map(ParentChildChunker.ChildChunk::ordinal).toList();
        assertThat(ordinals).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, ordinals.size()).boxed().toList());
    }
}
