package com.fitpilot.rag.processing;

import com.fitpilot.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ParentChildChunker {
    private final RagProperties properties;

    public ParentChildChunker(RagProperties properties) { this.properties = properties; }

    public List<ParentChunk> chunk(List<DocumentParser.ParsedSection> sections) {
        List<ParentChunk> result = new ArrayList<>();
        int parentOrdinal = 0;
        int childOrdinal = 0;
        for (DocumentParser.ParsedSection section : sections) {
            for (String parentText : split(section.content(), properties.getChunking().getParentMaxChars(), 0)) {
                List<ChildChunk> children = new ArrayList<>();
                for (String child : split(parentText, properties.getChunking().getChildMaxChars(),
                        properties.getChunking().getChildOverlapChars())) {
                    children.add(new ChildChunk(childOrdinal++, child));
                }
                result.add(new ParentChunk(parentOrdinal++, section.heading(), parentText, List.copyOf(children)));
            }
        }
        return List.copyOf(result);
    }

    private List<String> split(String text, int maxChars, int overlap) {
        if (text.length() <= maxChars) return text.isBlank() ? List.of() : List.of(text.trim());
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int hardEnd = Math.min(text.length(), start + maxChars);
            int end = boundary(text, start, hardEnd);
            if (end <= start) end = hardEnd;
            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) chunks.add(chunk);
            if (end >= text.length()) break;
            start = Math.max(start + 1, end - overlap);
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        }
        return chunks;
    }

    private int boundary(String text, int start, int hardEnd) {
        int minimum = start + (int) ((hardEnd - start) * 0.6);
        for (int index = hardEnd - 1; index >= minimum; index--) {
            char c = text.charAt(index);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                return index + 1;
            }
        }
        return hardEnd;
    }

    public record ParentChunk(int ordinal, String heading, String content, List<ChildChunk> children) {}
    public record ChildChunk(int ordinal, String content) {}
}
