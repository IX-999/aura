package com.agent.model;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.Gemini;
import com.google.genai.Client;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gemini backend served through Vertex AI.
 *
 * <p>Active when {@code agent.model.provider=gemini} (also the default when the property is
 * absent). Uses Application Default Credentials — no API key.
 */
@Component
@ConditionalOnProperty(name = "agent.model.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiModelProvider implements LlmModelProvider {

    @Value("${google.cloud.project-id}")
    private String projectId;

    @Value("${google.cloud.location:us-central1}")
    private String location;

    @Value("${google.cloud.model:gemini-2.5-flash}")
    private String modelName;

    @Override
    public BaseLlm createModel() {
        // Explicit Vertex AI client — uses ADC, no API key, no env-var dependency.
        Client vertexClient = Client.builder()
                .vertexAI(true)
                .project(projectId)
                .location(location)
                .build();

        return Gemini.builder()
                .modelName(modelName)
                .apiClient(vertexClient)
                .build();
    }

    @Override
    public String providerName() {
        return "gemini";
    }
}
