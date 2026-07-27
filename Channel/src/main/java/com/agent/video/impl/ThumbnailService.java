package com.agent.video.impl;

import com.agent.video.model.Segment;
import com.agent.video.model.Storyboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the YouTube thumbnail: pick the twist segment's image (else the first available
 * image), crop/scale to exactly 1280x720, and overlay the video title bottom-left with a
 * dark scrim behind it for contrast. See docs/VIDEO_PIPELINE.md.
 */
@Component
public class ThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);
    private static final String FONT_PATH = "C:/Windows/Fonts/arialbd.ttf";
    private static final int THUMB_WIDTH = 1280;
    private static final int THUMB_HEIGHT = 720;
    private static final int TITLE_WRAP_CHARS = 20;
    private static final int TITLE_MAX_LINES = 3;
    private static final long MAX_BYTES = 2L * 1024 * 1024; // YouTube's 2MB thumbnail limit

    public Path create(Storyboard storyboard, Map<Integer, Path> images, String title, Path workDir) throws Exception {
        Path sourceImage = pickSourceImage(storyboard, images);
        if (sourceImage == null) {
            throw new IllegalStateException("no images available to build a thumbnail from");
        }

        Path finalDir = Files.createDirectories(workDir.resolve("final"));
        Path logDir = Files.createDirectories(workDir.resolve("logs"));
        Path thumbOut = finalDir.resolve("thumbnail.jpg");

        String ffmpeg = ffmpegPath;
        String titleText = wrapTitle(title, TITLE_WRAP_CHARS, TITLE_MAX_LINES);
        Path textFile = workDir.resolve("thumbnail-text.txt");
        Files.write(textFile, titleText.getBytes(StandardCharsets.UTF_8));

        renderThumbnail(ffmpeg, sourceImage, textFile, thumbOut, logDir, 2);

        if (Files.size(thumbOut) > MAX_BYTES) {
            log.info("thumbnail {} bytes over YouTube's 2MB limit, re-encoding at lower quality",
                    Files.size(thumbOut));
            renderThumbnail(ffmpeg, sourceImage, textFile, thumbOut, logDir, 5);
        }

        return thumbOut;
    }

    // ------------------------------------------------------------- selection

    private Path pickSourceImage(Storyboard storyboard, Map<Integer, Path> images) {
        if (storyboard != null) {
            for (Segment segment : storyboard.segments()) {
                if (segment.twist()) {
                    Path img = images.get(segment.index());
                    if (img != null) {
                        return img;
                    }
                }
            }
        }
        return images.values().stream().findFirst().orElse(null);
    }

    // ------------------------------------------------------------- ffmpeg

    private void renderThumbnail(String ffmpeg, Path sourceImage, Path textFile, Path thumbOut,
                                 Path logDir, int qv) throws Exception {
        // Cover-crop to exactly 1280x720, then a dark scrim box behind the bottom-left
        // text region, then the title itself (bold, white, black shadow for contrast).
        String scrim = String.format(java.util.Locale.ROOT,
                "drawbox=x=0:y=%d:w=%d:h=%d:color=black@0.5:t=fill",
                THUMB_HEIGHT - 260, THUMB_WIDTH, 260);
        String drawtext = String.format(
                "drawtext=fontfile='%s':textfile='%s':fontcolor=white:fontsize=72:line_spacing=10:"
                        + "box=1:boxcolor=black@0.4:boxborderw=16:"
                        + "shadowcolor=black@0.8:shadowx=3:shadowy=3:"
                        + "x=60:y=main_h-text_h-60",
                escapeFilterPath(FONT_PATH), escapeFilterPath(textFile.toAbsolutePath().toString()));

        String filter = String.format(java.util.Locale.ROOT,
                "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,%s,%s",
                THUMB_WIDTH, THUMB_HEIGHT, THUMB_WIDTH, THUMB_HEIGHT, scrim, drawtext);

        List<String> args = List.of(
                "-y", "-i", sourceImage.toAbsolutePath().toString(),
                "-vf", filter,
                "-frames:v", "1",
                "-q:v", String.valueOf(qv),
                thumbOut.toAbsolutePath().toString());
        runFfmpeg(ffmpeg, args, logDir.resolve("thumbnail.log"), "thumbnail render");
    }

    private void runFfmpeg(String ffmpeg, List<String> args, Path logFile, String description) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));

        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "ffmpeg step '" + description + "' failed (exit " + exitCode + "). See log: "
                            + logFile.toAbsolutePath());
        }
    }

    private final String ffmpegPath;

    public ThumbnailService(com.agent.video.VideoPipelineProperties props) {
        this.ffmpegPath = props.ffmpegPath();
    }

    // ------------------------------------------------------------- text wrap

    /** Wraps text to maxChars/line, capping at maxLines (last line gets an ellipsis if truncated). */
    private String wrapTitle(String text, int maxChars, int maxLines) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] words = text.trim().split("\\s+");
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (line.length() > 0 && line.length() + 1 + word.length() > maxChars) {
                lines.add(line.toString());
                line = new StringBuilder();
                if (lines.size() == maxLines) {
                    break;
                }
            }
            if (line.length() > 0) {
                line.append(" ");
            }
            line.append(word);
        }
        if (lines.size() < maxLines && line.length() > 0) {
            lines.add(line.toString());
        }
        if (lines.size() > maxLines) {
            lines = lines.subList(0, maxLines);
        }
        if (lines.size() == maxLines && lines.get(maxLines - 1).length() >= maxChars) {
            String last = lines.get(maxLines - 1);
            lines.set(maxLines - 1, last.substring(0, Math.min(last.length(), maxChars - 1)).trim() + "…");
        }
        return String.join("\n", lines);
    }

    private String escapeFilterPath(String path) {
        // Inside a filter graph the option parser splits on ':' even within single
        // quotes, so the Windows drive colon must be escaped (C\:/...).
        return path.replace("\\", "/").replace(":", "\\:");
    }
}
