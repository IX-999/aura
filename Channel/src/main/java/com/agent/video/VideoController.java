package com.agent.video;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoPipelineService pipeline;
    private final VideoPipelineProperties props;
    private final YouTubeUploader youTubeUploader;
    private final YoutubeUploadProperties youtubeProps;

    public VideoController(VideoPipelineService pipeline, VideoPipelineProperties props,
                           YouTubeUploader youTubeUploader, YoutubeUploadProperties youtubeProps) {
        this.pipeline = pipeline;
        this.props = props;
        this.youTubeUploader = youTubeUploader;
        this.youtubeProps = youtubeProps;
    }

    /**
     * POST /api/videos/{runId}/publish — uploads an ALREADY-RENDERED run to YouTube using
     * its on-disk artifacts (no regeneration): final/&lt;slug&gt;.mp4, final/thumbnail.jpg,
     * title from the run's script.md. Optional body: {"privacyStatus": "private|unlisted|public"}.
     */
    @PostMapping("/{runId}/publish")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable String runId,
                                                       @RequestBody(required = false) Map<String, String> request) {
        try {
            java.nio.file.Path runDir = java.nio.file.Path.of(props.workDir(), runId);
            java.nio.file.Path finalDir = runDir.resolve("final");
            if (!java.nio.file.Files.isDirectory(finalDir)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error", "message", "no final/ dir for run " + runId));
            }

            // Prefer the title-slugged mp4; fall back to final.mp4.
            java.nio.file.Path video;
            try (var files = java.nio.file.Files.list(finalDir)) {
                video = files.filter(p -> p.getFileName().toString().endsWith(".mp4"))
                        .sorted(java.util.Comparator.comparing(
                                (java.nio.file.Path p) -> p.getFileName().toString().equals("final.mp4")))
                        .findFirst().orElse(null);
            }
            if (video == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error", "message", "no mp4 found in " + finalDir));
            }

            java.nio.file.Path thumbnail = finalDir.resolve("thumbnail.jpg");
            if (!java.nio.file.Files.exists(thumbnail)) {
                thumbnail = null;
            }

            String script = java.nio.file.Files.exists(runDir.resolve("script.md"))
                    ? java.nio.file.Files.readString(runDir.resolve("script.md"),
                            java.nio.charset.StandardCharsets.UTF_8)
                    : "";
            String title = firstJsonField(script, "title");
            if (title == null || title.isBlank()) {
                title = runId;
            }
            String description = firstJsonField(script, "narration");
            String suffix = youtubeProps.descriptionSuffix();
            if (suffix != null && !suffix.isBlank()) {
                description = (description == null ? "" : description) + "\n\n" + suffix;
            }

            String privacy = request != null && request.get("privacyStatus") != null
                    ? request.get("privacyStatus")
                    : (youtubeProps.privacyStatus() == null ? "private" : youtubeProps.privacyStatus());

            Map<String, String> result = youTubeUploader.upload(video, thumbnail, title,
                    description == null ? "" : description, privacy);

            Map<String, Object> body = new HashMap<>();
            body.put("status", "success");
            body.put("runId", runId);
            body.put("title", title);
            body.put("privacyStatus", privacy);
            body.putAll(result);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error", "type", e.getClass().getSimpleName(),
                    "message", String.valueOf(e.getMessage())));
        }
    }

    /**
     * POST /api/videos/youtube/{videoId}/visibility — flips an uploaded video's privacy.
     * Body: {"privacyStatus": "public" | "unlisted" | "private"}.
     */
    @PostMapping("/youtube/{videoId}/visibility")
    public ResponseEntity<Map<String, Object>> setVisibility(@PathVariable String videoId,
                                                             @RequestBody Map<String, String> request) {
        String privacy = request != null ? request.get("privacyStatus") : null;
        if (privacy == null || !java.util.Set.of("public", "unlisted", "private").contains(privacy)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error", "message", "privacyStatus must be public|unlisted|private"));
        }
        try {
            Map<String, String> result = youTubeUploader.setPrivacy(videoId, privacy);
            Map<String, Object> body = new HashMap<>();
            body.put("status", "success");
            body.putAll(result);
            body.put("url", "https://youtu.be/" + videoId);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error", "type", e.getClass().getSimpleName(),
                    "message", String.valueOf(e.getMessage())));
        }
    }

    private String firstJsonField(String text, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(text);
        return m.find() ? m.group(1).replace("\\\"", "\"") : null;
    }

    /**
     * POST /api/videos/generate
     * Body: { "topic": "...", "script": "full script text" }
     *   or  { "topic": "...", "scriptFile": "path, or 'latest' for the newest scripts/*.md" }
     * scriptFile avoids client-side JSON round-trips that have corrupted encoding in practice.
     * Returns immediately with a runId; poll GET /api/videos/{runId}.
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, String> request) {
        String script = request.get("script");
        String scriptFile = request.get("scriptFile");
        if (scriptFile != null && !scriptFile.isBlank()) {
            try {
                java.nio.file.Path path;
                if ("latest".equalsIgnoreCase(scriptFile.trim())) {
                    try (var files = java.nio.file.Files.list(java.nio.file.Path.of("scripts"))) {
                        path = files.filter(p -> p.getFileName().toString().endsWith(".md"))
                                .max(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                                .orElse(null);
                    }
                    if (path == null) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "status", "error", "message", "no scripts/*.md files found"));
                    }
                } else {
                    path = java.nio.file.Path.of(scriptFile.trim());
                }
                script = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error", "message", "failed to read scriptFile: " + e.getMessage()));
            }
        }
        if (script == null || script.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error", "message", "'script' or 'scriptFile' is required"));
        }
        String topic = request.getOrDefault("topic", "");
        if (topic.isBlank() || "latest".equalsIgnoreCase(topic.trim()) || "untitled".equalsIgnoreCase(topic.trim())) {
            // Name the video after the script's own title when the caller doesn't supply one.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .matcher(script);
            topic = m.find() ? m.group(1).replace("\\\"", "\"") : "untitled";
        }
        VideoJob job = pipeline.start(topic, script);
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted", "runId", job.runId(), "stage", job.stage().name()));
    }

    /** GET /api/videos/{runId} — stage, error (if any), artifact locations. */
    @GetMapping("/{runId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String runId) {
        VideoJob job = pipeline.get(runId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("runId", job.runId());
        body.put("topic", job.topic());
        body.put("stage", job.stage().name());
        body.put("startedAt", job.startedAt().toString());
        body.put("artifacts", job.artifacts());
        if (job.error() != null) {
            body.put("error", job.error());
        }
        return ResponseEntity.ok(body);
    }
}
