package com.agent.controller;

import com.agent.service.ScriptService;
import com.agent.video.VideoJob;
import com.agent.video.VideoPipelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-shot workflow: script generation → video render → (thumbnail, GCS, YouTube when enabled).
 *
 * POST /api/pipeline/run  {"niche":"finance"}  → {pipelineId}
 * GET  /api/pipeline/{pipelineId}              → combined script + video status
 */
@RestController
@RequestMapping("/api/pipeline")
public class PipelineController {

    private static final Logger log = LoggerFactory.getLogger(PipelineController.class);
    private static final Pattern TITLE = Pattern.compile("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final ScriptService scripts;
    private final VideoPipelineService videos;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "one-shot-pipeline");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, PipelineState> pipelines = new ConcurrentHashMap<>();

    public PipelineController(ScriptService scripts, VideoPipelineService videos) {
        this.scripts = scripts;
        this.videos = videos;
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody(required = false) Map<String, String> request) {
        Map<String, String> body = request == null ? Map.of() : request;
        String niche = body.getOrDefault("niche", "finance");
        String userId = body.getOrDefault("userId", "pipeline");

        String pipelineId = "pl-" + UUID.randomUUID().toString().substring(0, 8);
        PipelineState state = new PipelineState(niche);
        pipelines.put(pipelineId, state);

        executor.submit(() -> {
            try {
                state.stage = "GENERATING_SCRIPT";
                Map<String, Object> result = scripts.generate(niche, userId);
                // If the agent's final saveScript was REJECTED (length gate), the captured
                // script is under-length — one full regeneration attempt before accepting it.
                if ("success".equals(result.get("status"))
                        && Boolean.FALSE.equals(result.get("saveAccepted"))) {
                    log.warn("[{}] script save was rejected (likely under length); regenerating once",
                            pipelineId);
                    state.stage = "GENERATING_SCRIPT_RETRY";
                    Map<String, Object> retry = scripts.generate(niche, userId);
                    if ("success".equals(retry.get("status"))
                            && retry.get("script") instanceof String s2 && !s2.isBlank()) {
                        result = retry;
                    }
                }
                if (!"success".equals(result.get("status"))
                        || !(result.get("script") instanceof String script) || script.isBlank()) {
                    state.stage = "FAILED";
                    state.error = "script generation failed: " + result.getOrDefault("message", result);
                    return;
                }
                if (Boolean.FALSE.equals(result.get("saveAccepted"))) {
                    state.warning = "script save rejected twice — video may run under 20 minutes";
                }
                Matcher m = TITLE.matcher(script);
                state.title = m.find() ? m.group(1).replace("\\\"", "\"") : niche;

                state.stage = "VIDEO";
                VideoJob job = videos.start(state.title, script);
                state.runId = job.runId();
                // VideoJob runs on the video pipeline's own executor; this thread only
                // needs to hand over. Status polling reads the job directly.
            } catch (Exception e) {
                log.error("[{}] one-shot pipeline failed", pipelineId, e);
                state.stage = "FAILED";
                state.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted", "pipelineId", pipelineId, "niche", niche));
    }

    @GetMapping("/{pipelineId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String pipelineId) {
        PipelineState state = pipelines.get(pipelineId);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("pipelineId", pipelineId);
        body.put("niche", state.niche);
        body.put("startedAt", state.startedAt.toString());
        if (state.title != null) {
            body.put("title", state.title);
        }
        if (state.error != null) {
            body.put("error", state.error);
        }
        if (state.warning != null) {
            body.put("warning", state.warning);
        }

        VideoJob job = state.runId != null ? videos.get(state.runId) : null;
        if (job != null) {
            body.put("runId", job.runId());
            body.put("stage", job.stage().name());
            body.put("artifacts", job.artifacts());
            if (job.error() != null) {
                body.put("error", job.error());
            }
        } else {
            body.put("stage", state.stage);
        }
        return ResponseEntity.ok(body);
    }

    private static final class PipelineState {
        final String niche;
        final Instant startedAt = Instant.now();
        volatile String stage = "QUEUED";
        volatile String title;
        volatile String runId;
        volatile String error;
        volatile String warning;

        PipelineState(String niche) {
            this.niche = niche;
        }
    }
}
