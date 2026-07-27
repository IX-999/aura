package com.agent.service;

import com.agent.agent.StoryTellerAgent;
import com.agent.tools.StoryTellerTools;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the storyteller agent end to end for a niche and returns the finished script.
 * Extracted from StoryTellerController so the one-shot pipeline endpoint can reuse it.
 */
@Service
public class ScriptService {

    private static final String APP_NAME = "storyteller-app";

    private final Runner runner;
    private final InMemorySessionService sessionService;

    public ScriptService(StoryTellerAgent agent) {
        this.sessionService = new InMemorySessionService();
        this.runner = new Runner(
                agent.getAgent(), APP_NAME, new InMemoryArtifactService(), sessionService);
    }

    /**
     * Generates a complete script for the niche. Returns keys: status, script, scriptSource,
     * wordCount, toolTrace, sessionId (and message/type on error).
     */
    public Map<String, Object> generate(String niche, String userId) {
        Map<String, Object> response = new HashMap<>();
        try {
            Session session = sessionService.createSession(APP_NAME, userId).blockingGet();

            Content message = Content.fromParts(Part.fromText(
                    "Find trending topics in the '" + niche + "' niche. "
                    + "Screen them for content policy, discard anything already published, "
                    + "pick the one with the strongest story, research it thoroughly, "
                    + "and write the complete 20-25 minute script."));

            // The agent may emit the script text more than once (draft, then a revision
            // around the saveScript call). The authoritative copy is the scriptText the
            // agent passed to saveScript; the current text block is only a fallback.
            StringBuilder currentText = new StringBuilder();
            StringBuilder toolTrace = new StringBuilder();
            AtomicReference<String> savedScript = new AtomicReference<>();
            AtomicReference<String> lastSaveStatus = new AtomicReference<>();

            Flowable<Event> events = runner.runAsync(userId, session.id(), message);

            events.blockingForEach(event -> {
                if (event.content().isEmpty() || event.content().get().parts().isEmpty()) return;

                event.content().get().parts().get().forEach(part -> {
                    part.text().ifPresent(text -> {
                        if (!text.isBlank() && "storyteller-agent".equals(event.author())) {
                            currentText.append(text);
                        }
                    });
                    part.functionCall().ifPresent(call -> {
                        toolTrace.append("CALL ").append(call.name().orElse("?")).append('\n');
                        if ("saveScript".equals(call.name().orElse(""))) {
                            Object text = call.args().map(a -> a.get("scriptText")).orElse(null);
                            if (text instanceof String s && !s.isBlank()) {
                                savedScript.set(s);
                            }
                        }
                        currentText.setLength(0);
                    });
                    // Track whether the FINAL saveScript was actually accepted — the length
                    // gate rejects short scripts, and a rejected script must not silently
                    // become an under-length video.
                    part.functionResponse().ifPresent(fr -> {
                        if ("saveScript".equals(fr.name().orElse(""))) {
                            Object status = fr.response().map(r -> r.get("status")).orElse(null);
                            lastSaveStatus.set(String.valueOf(status));
                        }
                    });
                });
            });

            String script = StoryTellerTools.repairMojibake(
                    savedScript.get() != null ? savedScript.get() : currentText.toString());

            // Belt-and-suspenders topic ledger: saveScript appends on ACCEPTED saves, but a
            // rejected-yet-still-used script (the controller captures the call args either
            // way) must also be recorded or the dedupe never learns about it.
            recordTopic(script);

            response.put("status", "success");
            response.put("saveAccepted", "success".equals(lastSaveStatus.get()));
            response.put("niche", niche);
            response.put("sessionId", session.id());
            response.put("script", script);
            response.put("scriptSource", savedScript.get() != null ? "saveScript" : "finalText");
            response.put("wordCount", script.split("\\s+").length);
            response.put("toolTrace", toolTrace.toString());
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            response.put("type", e.getClass().getSimpleName());
        }
        return response;
    }

    private static void recordTopic(String script) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .matcher(script == null ? "" : script);
            if (!m.find()) {
                return;
            }
            String title = m.group(1).replace("\\\"", "\"").trim();
            java.nio.file.Path ledger = java.nio.file.Path.of("published-topics.txt");
            boolean known = java.nio.file.Files.exists(ledger)
                    && java.nio.file.Files.readAllLines(ledger).stream()
                            .anyMatch(l -> l.trim().equalsIgnoreCase(title));
            if (!known && !title.isEmpty()) {
                java.nio.file.Files.writeString(ledger, title + System.lineSeparator(),
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (Exception ignored) {
            // Ledger is best-effort; never fail script delivery over it.
        }
    }
}
