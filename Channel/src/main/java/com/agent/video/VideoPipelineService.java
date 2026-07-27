package com.agent.video;

import com.agent.video.impl.ThumbnailService;
import com.agent.video.model.NarrationClip;
import com.agent.video.model.RenderManifest;
import com.agent.video.model.Segment;
import com.agent.video.model.SegmentType;
import com.agent.video.model.Storyboard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates script → storyboard → narration → images → render → upload.
 * Single-threaded executor: one video renders at a time; further requests queue.
 */
@Service
public class VideoPipelineService {

    private static final Logger log = LoggerFactory.getLogger(VideoPipelineService.class);

    private final ScriptParser parser;
    private final NarrationService narration;
    private final VisualService visuals;
    private final TimelineBuilder timeline;
    private final VideoRenderer renderer;
    private final MediaStore mediaStore;
    private final VideoPipelineProperties props;
    private final ThumbnailService thumbnailService;
    private final YouTubeUploader youTubeUploader;
    private final YoutubeUploadProperties youtubeProps;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "video-pipeline");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, VideoJob> jobs = new ConcurrentHashMap<>();

    public VideoPipelineService(ScriptParser parser, NarrationService narration,
                                VisualService visuals, TimelineBuilder timeline,
                                VideoRenderer renderer, MediaStore mediaStore,
                                VideoPipelineProperties props, ThumbnailService thumbnailService,
                                YouTubeUploader youTubeUploader, YoutubeUploadProperties youtubeProps) {
        this.parser = parser;
        this.narration = narration;
        this.visuals = visuals;
        this.timeline = timeline;
        this.renderer = renderer;
        this.mediaStore = mediaStore;
        this.props = props;
        this.thumbnailService = thumbnailService;
        this.youTubeUploader = youTubeUploader;
        this.youtubeProps = youtubeProps;
    }

    public VideoJob start(String topic, String scriptText) {
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        VideoJob job = new VideoJob(runId, topic);
        jobs.put(runId, job);
        executor.submit(() -> run(job, topic, scriptText));
        return job;
    }

    public VideoJob get(String runId) {
        return jobs.get(runId);
    }

    private void run(VideoJob job, String topic, String scriptText) {
        try {
            Path workDir = Path.of(props.workDir(), job.runId());
            Path audioDir = Files.createDirectories(workDir.resolve("audio"));
            Path imageDir = Files.createDirectories(workDir.resolve("images"));
            Files.createDirectories(workDir.resolve("final"));

            Files.writeString(workDir.resolve("script.md"), scriptText);

            job.stage(VideoJob.Stage.PARSING);
            Storyboard storyboard = parser.parse(topic, scriptText);
            if (storyboard.segments().isEmpty()) {
                throw new IllegalArgumentException("Script parsed to zero segments — is it marker-annotated?");
            }
            Path storyboardJson = workDir.resolve("storyboard.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(storyboardJson.toFile(), storyboard);
            log.info("[{}] storyboard: {} segments", job.runId(), storyboard.segments().size());

            // Narration and images have no dependency on each other — overlap them.
            job.stage(VideoJob.Stage.NARRATING);
            java.util.concurrent.CompletableFuture<Map<Integer, Path>> imagesFuture =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return visuals.generateImages(storyboard, imageDir);
                        } catch (Exception e) {
                            throw new java.util.concurrent.CompletionException(e);
                        }
                    });
            List<NarrationClip> clips = narration.synthesize(storyboard, audioDir);

            job.stage(VideoJob.Stage.GENERATING_IMAGES);
            Map<Integer, Path> images = imagesFuture.join();

            job.stage(VideoJob.Stage.RENDERING);
            RenderManifest manifest = timeline.build(job.runId(), storyboard, clips, images);
            Path manifestJson = workDir.resolve("manifest.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(manifestJson.toFile(), manifest);
            Path finalVideo = renderer.render(manifest, workDir);

            // Name the deliverable after the video title, not "final.mp4".
            String slug = topic.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
            String videoName = (slug.isBlank() ? "final" : slug) + ".mp4";
            Path namedVideo = finalVideo.resolveSibling(videoName);
            Files.copy(finalVideo, namedVideo, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            job.artifact("video", namedVideo.toAbsolutePath().toString());

            Path thumbnail = thumbnailService.create(storyboard, images, topic, workDir);
            job.artifact("thumbnail", thumbnail.toAbsolutePath().toString());

            if (props.gcs() != null && props.gcs().enabled()) {
                job.stage(VideoJob.Stage.UPLOADING);
                Map<String, Path> artifacts = new HashMap<>();
                artifacts.put("script.md", workDir.resolve("script.md"));
                artifacts.put("storyboard.json", storyboardJson);
                artifacts.put("manifest.json", manifestJson);
                artifacts.put(videoName, namedVideo);
                artifacts.put(thumbnail.getFileName().toString(), thumbnail);
                // Title-first GCS folder so runs are findable by name; runId suffix keeps
                // repeat topics from colliding.
                String gcsFolder = slug.isBlank() ? job.runId() : slug + "-" + job.runId();
                mediaStore.uploadRun(gcsFolder, artifacts).forEach(job::artifact);
            }

            if (youtubeProps != null && youtubeProps.enabled()) {
                job.stage(VideoJob.Stage.UPLOADING_YOUTUBE);
                try {
                    String narrationForDescription = storyboard.segments().stream()
                            .filter(s -> s.type() == SegmentType.BROLL)
                            .map(Segment::narration)
                            .filter(n -> n != null && !n.isBlank())
                            .findFirst()
                            .orElseGet(() -> storyboard.segments().isEmpty() ? "" : storyboard.segments().get(0).narration());
                    String suffix = youtubeProps.descriptionSuffix();
                    String description = narrationForDescription
                            + (suffix != null && !suffix.isBlank() ? "\n\n" + suffix : "");
                    Map<String, String> ytResult = youTubeUploader.upload(
                            namedVideo, thumbnail, job.topic(), description, youtubeProps.privacyStatus());
                    if (ytResult.get("videoId") != null) {
                        job.artifact("youtubeVideoId", ytResult.get("videoId"));
                    }
                    if (ytResult.get("url") != null) {
                        job.artifact("youtubeUrl", ytResult.get("url"));
                    }
                    if (ytResult.get("thumbnailError") != null) {
                        job.artifact("thumbnailError", ytResult.get("thumbnailError"));
                    }
                } catch (Exception e) {
                    // A publish failure must not lose an otherwise-finished video.
                    log.error("[{}] YouTube publish failed", job.runId(), e);
                    job.artifact("youtubeError", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }

            job.stage(VideoJob.Stage.DONE);
            log.info("[{}] done: {}", job.runId(), job.artifacts());
        } catch (Exception e) {
            log.error("[{}] pipeline failed at stage {}", job.runId(), job.stage(), e);
            job.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
