package com.agent.controller;

import com.agent.service.ScriptService;
import com.agent.tools.TrendingTopicsTool;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/scripts")
public class StoryTellerController {

    private final ScriptService scripts;

    public StoryTellerController(ScriptService scripts) {
        this.scripts = scripts;
    }

    /**
     * POST /api/scripts/generate
     * Body: { "niche": "science", "userId": "pipeline" }
     */
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody Map<String, String> request) {
        String niche = request.getOrDefault("niche", "science");
        String userId = request.getOrDefault("userId", "pipeline");
        return scripts.generate(niche, userId);
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
