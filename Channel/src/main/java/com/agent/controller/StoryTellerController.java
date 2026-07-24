package com.agent.controller;

import com.agent.agent.StoryTellerAgent;
import com.agent.tools.TrendingTopicsTool;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/scripts")
public class StoryTellerController {

    private static final String APP_NAME = "storyteller-app";

    private final Runner runner;
    private final InMemorySessionService sessionService;

    public StoryTellerController(StoryTellerAgent agent) {
        this.sessionService = new InMemorySessionService();
        // ADK 1.7.0 Runner requires an app name and an artifact service.
        this.runner = new Runner(
                agent.getAgent(), APP_NAME, new InMemoryArtifactService(), sessionService);
    }

    /**
     * POST /api/scripts/generate
     * Body: { "niche": "science", "userId": "pipeline" }
     */
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody Map<String, String> request) {
        String niche = request.getOrDefault("niche", "science");
        String userId = request.getOrDefault("userId", "pipeline");

        Map<String, Object> response = new HashMap<>();

        try {
            Session session = sessionService.createSession(APP_NAME, userId).blockingGet();

            Content message = Content.fromParts(Part.fromText(
                    "Find trending topics in the '" + niche + "' niche. "
                    + "Screen them for content policy, discard anything already published, "
                    + "pick the one with the strongest story, research it thoroughly, "
                    + "and write the complete 20-25 minute script."));

            StringBuilder script = new StringBuilder();
            StringBuilder toolTrace = new StringBuilder();

            Flowable<Event> events = runner.runAsync(userId, session.id(), message);

            events.blockingForEach(event -> {
                // Event.content() is Optional<Content>; Content.parts() is Optional<List<Part>>.
                if (event.content().isEmpty() || event.content().get().parts().isEmpty()) return;

                event.content().get().parts().get().forEach(part -> {
                    // Capture the agent's narrative output
                    part.text().ifPresent(text -> {
                        if (!text.isBlank() && "storyteller-agent".equals(event.author())) {
                            script.append(text);
                        }
                    });
                    // Log tool activity so you can see the agent's reasoning path
                    part.functionCall().ifPresent(call ->
                            toolTrace.append("CALL ").append(call.name().orElse("?")).append('\n'));
                });
            });

            response.put("status", "success");
            response.put("niche", niche);
            response.put("sessionId", session.id());
            response.put("script", script.toString());
            response.put("wordCount", script.toString().split("\\s+").length);
            response.put("toolTrace", toolTrace.toString());

        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            response.put("type", e.getClass().getSimpleName());
        }

        return response;
    }

    /**
     * POST /api/scripts/trending
     * Body: { "niche": "science", "maxResults": "10" }
     *
     * Calls the YouTube + TikTok trending tool directly, WITHOUT invoking the LLM.
     * Use this to verify the trending integrations in isolation (it works even
     * without Vertex AI / model credentials).
     */
    @PostMapping("/trending")
    public Map<String, String> trending(@RequestBody(required = false) Map<String, String> request) {
        Map<String, String> body = request == null ? Map.of() : request;
        String niche = body.getOrDefault("niche", "science");
        int maxResults = parseInt(body.get("maxResults"), 10);
        return TrendingTopicsTool.getTrendingTopics(niche, maxResults);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
