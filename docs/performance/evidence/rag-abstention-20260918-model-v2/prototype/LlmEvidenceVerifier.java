package com.fitpilot.rag.infrastructure;

import com.fitpilot.llm.config.LlmProperties;
import com.fitpilot.llm.domain.LlmModels;
import com.fitpilot.llm.infrastructure.OpenAiCompatibleClient;
import com.fitpilot.rag.application.EvidenceVerifier;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class LlmEvidenceVerifier implements EvidenceVerifier {
    private final LlmProperties properties;
    private final OpenAiCompatibleClient client;

    public LlmEvidenceVerifier(LlmProperties properties, OpenAiCompatibleClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public boolean available() {
        return properties.isEnabled() && (properties.getPrimary().configured() || properties.getFallback().configured());
    }

    @Override
    public boolean supports(String query, String passage) {
        String prompt = "问题：" + query + "\n文段：" + passage;
        String answer = client.complete(null, LlmModels.Task.EVIDENCE_VERIFICATION, prompt, false)
                .content().strip().toUpperCase(Locale.ROOT);
        return switch (answer) {
            case "SUPPORTED" -> true;
            case "ABSENT" -> false;
            default -> throw new IllegalStateException("invalid evidence verifier response");
        };
    }
}
