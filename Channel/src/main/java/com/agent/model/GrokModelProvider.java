package com.agent.model;

import com.google.adk.models.BaseLlm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Grok backend served through xAI's OpenAI-compatible Chat Completions API.
 *
 * <p>Active when {@code agent.model.provider=grok}. Requires an xAI API key, supplied via the
 * {@code XAI_API_KEY} environment variable (mapped to {@code grok.api-key} in
 * application.properties) — never hardcode it.
 */
@Component
@ConditionalOnProperty(name = "agent.model.provider", havingValue = "grok")
public class GrokModelProvider implements LlmModelProvider {

    @Value("${grok.base-url:https://api.x.ai/v1}")
    private String baseUrl;

    @Value("${grok.model:grok-4.3}")
    private String modelName;

    @Value("${grok.api-key:}")
    private String apiKey;

    @Override
    public BaseLlm createModel() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "grok.api-key is not set. Provide your xAI key via the XAI_API_KEY environment "
                    + "variable (or the grok.api-key property) before starting with the grok provider.");
        }
        return new GrokChatModel(modelName, baseUrl, apiKey);
    }

    @Override
    public String providerName() {
        return "grok";
    }
}
