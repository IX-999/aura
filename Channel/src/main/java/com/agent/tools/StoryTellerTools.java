package com.agent.tools;

import com.agent.policy.ContentPolicy;
import com.google.adk.tools.Annotations.Schema;

import java.util.HashMap;
import java.util.Map;

/**
 * Tools exposed to the storyteller agent.
 *
 * IMPORTANT: compile with -parameters (maven.compiler.parameters=true) or ADK
 * cannot read parameter names when it builds the tool schema.
 *
 * All methods must return Map<String, String> (or another ADK-serializable type)
 * and should never throw — return an error status instead so the agent can recover.
 */
public class StoryTellerTools {

    /**
     * Step 2: hard content screen. The agent MUST call this before committing
     * to a topic. Deterministic — not subject to prompt persuasion.
     *
     * Trending discovery now lives in {@link TrendingTopicsTool} (YouTube +
     * TikTok). This class keeps the screening, dedupe, research, and save tools.
     */
    public static Map<String, String> screenTopicSafety(
            @Schema(description = "The candidate topic to screen for content policy violations")
            String topic) {

        ContentPolicy.Result result = ContentPolicy.screen(topic);

        Map<String, String> out = new HashMap<>();
        out.put("topic", topic);
        out.put("verdict", result.verdict().name());
        out.put("reason", result.reason());
        out.put("approved", String.valueOf(!result.isBlocked()));
        result.matchedTerm().ifPresent(t -> out.put("matchedTerm", t));
        return out;
    }

    /**
     * Step 3: dedupe against what the channel already published.
     * Swap for a Firestore query on published_topics keyed by SHA-256 of the
     * normalized topic string.
     */
    public static Map<String, String> checkTopicPublished(
            @Schema(description = "Topic to check against the channel's publish history")
            String topic) {

        Map<String, String> out = new HashMap<>();
        // TODO: Firestore lookup on published_topics
        out.put("topic", topic);
        out.put("alreadyPublished", "false");
        out.put("status", "success");
        return out;
    }

    /**
     * Step 4: research. This is the accuracy backbone — the agent is instructed
     * that every factual claim in the script must trace back to this output.
     *
     * Wire this to Vertex AI Search with Google Search grounding, or to a
     * Custom Search API call. Return real source URLs so the agent can cite them.
     */
    public static Map<String, String> researchTopic(
            @Schema(description = "The topic to research in depth")
            String topic,
            @Schema(description = "Specific angle or question to investigate, or 'general' for an overview")
            String angle) {

        Map<String, String> out = new HashMap<>();
        try {
            // TODO: replace with grounded search. Return findings + source URLs.
            out.put("status", "success");
            out.put("topic", topic);
            out.put("angle", angle);
            out.put("findings", "PLACEHOLDER — wire this to grounded search. "
                    + "Return 5-10 concrete facts with dates, numbers, and names.");
            out.put("sources", "PLACEHOLDER — return real URLs here.");
            out.put("confidence", "low");
        } catch (Exception e) {
            out.put("status", "error");
            out.put("message", "Research failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Step 5: persist the finished script so the downstream audio/video stages
     * can pick it up. Swap for a GCS write.
     */
    public static Map<String, String> saveScript(
            @Schema(description = "The topic the script covers")
            String topic,
            @Schema(description = "The full script text including all markers")
            String scriptText) {

        Map<String, String> out = new HashMap<>();
        // TODO: write to gs://your-bucket/runs/{runId}/script.md
        out.put("status", "success");
        out.put("topic", topic);
        out.put("wordCount", String.valueOf(scriptText.split("\\s+").length));
        out.put("savedTo", "gs://placeholder/script.md");
        return out;
    }
}