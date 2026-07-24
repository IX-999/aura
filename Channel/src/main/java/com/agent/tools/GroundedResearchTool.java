package com.agent.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.adk.tools.Annotations.Schema;
import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.GroundingChunk;
import com.google.genai.types.Tool;

import jakarta.annotation.PostConstruct;

/**
 * Research via Gemini's native Google Search grounding.
 *
 * ADK's FunctionTool.create(Class, "methodName") requires a STATIC method, but
 * static methods can't use Spring @Value injection. The Config inner class
 * bridges that gap: Spring populates it at startup, the static tool method
 * reads from it.
 *
 * The grounded Gemini call is made INSIDE this tool rather than registering
 * GoogleSearchTool on the agent -- that sidesteps ADK's restriction on mixing
 * built-in tools with custom FunctionTools on the same agent.
 */
public class GroundedResearchTool {

    // ---------------------------------------------------------------
    // Config bridge -- Spring writes here at startup.
    // ---------------------------------------------------------------
    @Component
    public static class Config {

        @Value("${google.cloud.project-id}")
        private String projectId;

        @Value("${google.cloud.location:us-central1}")
        private String location;

        /**
         * Model used for RESEARCH calls specifically. Kept separate from the
         * script-writing model so you can ground on a cheap model and write on
         * a stronger one.
         */
        @Value("${google.cloud.research-model:${google.cloud.model:gemini-2.5-flash}}")
        private String researchModel;

        @PostConstruct
        void publish() {
            GroundedResearchTool.projectId = projectId;
            GroundedResearchTool.location = location;
            GroundedResearchTool.model = researchModel;
        }
    }

    private static volatile String projectId;
    private static volatile String location;
    private static volatile String model;
    private static volatile Client client;

    private static Client client() {
        if (client == null) {
            synchronized (GroundedResearchTool.class) {
                if (client == null) {
                    if (projectId == null || projectId.isBlank()) {
                        throw new IllegalStateException(
                                "GroundedResearchTool config not initialized. Ensure "
                                + "GroundedResearchTool.Config is component-scanned and "
                                + "google.cloud.project-id is set.");
                    }
                    client = Client.builder()
                            .vertexAI(true)
                            .project(projectId)
                            .location(location)
                            .build();
                }
            }
        }
        return client;
    }

    // ---------------------------------------------------------------
    // The tool itself
    // ---------------------------------------------------------------
    public static Map<String, String> researchTopic(
            @Schema(description = "The topic to research in depth") String topic,
            @Schema(description = "Specific angle to investigate, or 'general' for a broad overview") String angle) {

        Map<String, String> out = new HashMap<>();

        try {
            Tool googleSearch = Tool.builder()
                    .googleSearch(GoogleSearch.builder().build())
                    .build();

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .tools(List.of(googleSearch))
                    .temperature(0.2f) // fact gathering, not prose -- keep it tight
                    .build();

            GenerateContentResponse response = client().models.generateContent(model, buildPrompt(topic, angle), config);

            String findings = response.text();
            Set<String> sources = extractSources(response);

            out.put("status", "success");
            out.put("topic", topic);
            out.put("angle", angle);
            out.put("findings", findings == null ? "" : findings);
            out.put("sources", String.join("\n", sources));
            out.put("sourceCount", String.valueOf(sources.size()));
            out.put("confidence",
                    sources.size() >= 3 ? "high" : sources.isEmpty() ? "low" : "medium");

        } catch (Exception e) {
            out.put("status", "error");
            out.put("topic", topic);
            out.put("message", "Research failed: " + e.getMessage());
            out.put("confidence", "low");
        }

        return out;
    }

    private static String buildPrompt(String topic, String angle) {
        return """
            Research this topic thoroughly using web search. Return ONLY factual
            findings -- no narrative framing, no script writing, no storytelling.

            TOPIC: %s
            ANGLE: %s

            Return exactly these four sections:

            1. FACTS -- ten concrete facts. Each must carry specifics: names, dates,
               numbers, places. "It was expensive" is useless. "It cost $440 million
               in 1985 dollars" is what I need.

            2. THE SURPRISE -- the single most counterintuitive or little-known thing
               you found. If the widely believed version of this story is wrong, state
               exactly how it's wrong and what actually happened.

            3. DISPUTED -- anything where credible sources disagree, or where a finding
               is a working hypothesis rather than settled fact. Label each clearly.

            4. DEAD ENDS -- claims you encountered but could NOT verify. List them so
               the writer knows not to use them.

            If you cannot find solid sourcing for this topic, say so plainly. Do not
            fill gaps with plausible-sounding material.
            """.formatted(topic, angle);
    }

    /**
     * Pull real source URLs out of groundingMetadata.
     *
     * NOTE: accessor names in the Java genai SDK have shifted between versions.
     * If this fails to compile, open GroundingMetadata in your jar -- the data
     * is there, only the getter names move.
     */
    private static Set<String> extractSources(GenerateContentResponse response) {
        Set<String> urls = new LinkedHashSet<>();
        try {
            List<Candidate> candidates = response.candidates().orElse(new ArrayList<>());
            for (Candidate candidate : candidates) {
                candidate.groundingMetadata().ifPresent(meta
                        -> meta.groundingChunks().ifPresent(chunks -> {
                            for (GroundingChunk chunk : chunks) {
                                chunk.web().ifPresent(web -> {
                                    String uri = web.uri().orElse(null);
                                    String title = web.title().orElse("");
                                    if (uri != null) {
                                        urls.add(title.isBlank() ? uri : title + " -- " + uri);
                                    }
                                });
                            }
                        }));
            }
        } catch (Exception ignored) {
            // Best-effort. Findings remain usable without citation extraction.
        }
        return urls;
    }
}
