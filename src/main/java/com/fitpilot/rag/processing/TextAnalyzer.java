package com.fitpilot.rag.processing;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TextAnalyzer {
    private static final Pattern TERM = Pattern.compile("[\\p{IsHan}]|[a-z0-9]+(?:[.-][a-z0-9]+)*");

    public List<String> tokens(String input) {
        String normalized = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        Matcher matcher = TERM.matcher(normalized);
        List<String> raw = new ArrayList<>();
        while (matcher.find()) raw.add(matcher.group());

        List<String> expanded = new ArrayList<>(raw);
        String previousHan = null;
        for (String token : raw) {
            if (isHan(token)) {
                if (previousHan != null) expanded.add(previousHan + token);
                previousHan = token;
            } else {
                previousHan = null;
            }
        }
        return List.copyOf(expanded);
    }

    public String lexicalText(String input) {
        return String.join(" ", tokens(input));
    }

    private boolean isHan(String token) {
        return token.codePointCount(0, token.length()) == 1
                && Character.UnicodeScript.of(token.codePointAt(0)) == Character.UnicodeScript.HAN;
    }
}
