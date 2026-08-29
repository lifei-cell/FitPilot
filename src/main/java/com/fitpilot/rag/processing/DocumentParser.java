package com.fitpilot.rag.processing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentParser {
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$");

    public List<ParsedSection> parse(String format, String title, String content) {
        if (!"MARKDOWN".equalsIgnoreCase(format)) {
            return List.of(new ParsedSection(title, normalize(content)));
        }
        List<ParsedSection> sections = new ArrayList<>();
        String heading = title;
        StringBuilder body = new StringBuilder();
        for (String line : content.replace("\r\n", "\n").split("\n", -1)) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                addSection(sections, heading, body);
                heading = matcher.group(1).trim();
                body.setLength(0);
            } else {
                body.append(cleanMarkdown(line)).append('\n');
            }
        }
        addSection(sections, heading, body);
        return sections.isEmpty() ? List.of(new ParsedSection(title, normalize(content))) : sections;
    }

    private void addSection(List<ParsedSection> sections, String heading, StringBuilder body) {
        String text = normalize(body.toString());
        if (!text.isBlank()) sections.add(new ParsedSection(heading, text));
    }

    private String cleanMarkdown(String line) {
        return line.replaceAll("^\\s*[-*+]\\s+", "")
                .replaceAll("^\\s*\\d+[.)]\\s+", "")
                .replaceAll("[*_`>]", "")
                .replaceAll("!?(\\[([^]]+)])\\([^)]+\\)", "$2");
    }

    private String normalize(String text) {
        return text.replace("\r\n", "\n").replaceAll("[ \\t]+", " ")
                .replaceAll("\n{3,}", "\n\n").trim();
    }

    public record ParsedSection(String heading, String content) {}
}
