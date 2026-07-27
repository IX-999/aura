package com.agent.video.impl;

import com.agent.video.VideoPipelineProperties;
import com.agent.video.VideoRenderer;
import com.agent.video.model.RenderManifest;
import com.agent.video.model.SegmentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ffmpeg assembly: per-segment Ken-Burns/graphic clips -> concat -> mux with narration
 * (+ optional background music). See docs/VIDEO_PIPELINE.md.
 *
 * NOTE: ffmpeg is not installed in this dev environment — this class cannot be exercised
 * end to end here. Invocations are built to match the documented ffmpeg contract exactly;
 * verify with a real ffmpeg install before relying on it in production.
 */
@Component
public class FfmpegVideoRenderer implements VideoRenderer {

    private static final Logger log = LoggerFactory.getLogger(FfmpegVideoRenderer.class);
    private static final int WRAP_CHARS = 40;
    private static final String FONT_PATH = "C:/Windows/Fonts/arialbd.ttf";

    private final VideoPipelineProperties props;

    public FfmpegVideoRenderer(VideoPipelineProperties props) {
        this.props = props;
    }

    @Override
    public Path render(RenderManifest manifest, Path workDir) throws Exception {
        String ffmpeg = props.ffmpegPath();
        checkFfmpegAvailable(ffmpeg);

        Path logDir = Files.createDirectories(workDir.resolve("logs"));
        Path clipsDir = Files.createDirectories(workDir.resolve("clips"));
        Path silenceDir = Files.createDirectories(workDir.resolve("audio").resolve("silence"));
        Path finalDir = Files.createDirectories(workDir.resolve("final"));

        Path narrationWav = buildNarrationTrack(manifest, workDir, silenceDir, logDir, ffmpeg);
        List<Path> clipPaths = buildClips(manifest, clipsDir, logDir, ffmpeg);
        Path videoMp4 = concatClips(clipPaths, workDir, logDir, ffmpeg);
        Path captionsAss = buildCaptions(manifest, workDir);
        Path finalMp4 = mux(videoMp4, narrationWav, captionsAss, finalDir, logDir, ffmpeg);

        return finalMp4;
    }

    // ---------------------------------------------------------------- setup

    private void checkFfmpegAvailable(String ffmpeg) throws Exception {
        try {
            Process process = new ProcessBuilder(ffmpeg, "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg -version did not succeed (binary: " + ffmpeg + ")");
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "ffmpeg binary not found or not runnable at '" + ffmpeg
                            + "'. Set video.ffmpeg-path to a valid ffmpeg executable.", e);
        }
    }

    // ------------------------------------------------------------- audio track

    private Path buildNarrationTrack(RenderManifest manifest, Path workDir, Path silenceDir,
                                     Path logDir, String ffmpeg) throws Exception {
        List<String> concatLines = new ArrayList<>();
        int i = 0;
        for (RenderManifest.Entry entry : manifest.entries()) {
            concatLines.add("file '" + escapeConcatPath(entry.audioPath()) + "'");

            Path silenceWav = silenceDir.resolve(String.format("gap-%03d.wav", i));
            generateSilence(entry.gapAfterSeconds(), silenceWav, logDir, ffmpeg);
            concatLines.add("file '" + escapeConcatPath(silenceWav.toAbsolutePath().toString()) + "'");
            i++;
        }

        Path listFile = workDir.resolve("narration_concat.txt");
        Files.write(listFile, String.join("\n", concatLines).getBytes(StandardCharsets.UTF_8));

        Path narrationWav = workDir.resolve("narration.wav");
        List<String> args = List.of(
                "-y", "-f", "concat", "-safe", "0",
                "-i", listFile.toAbsolutePath().toString(),
                "-c", "copy",
                narrationWav.toAbsolutePath().toString());
        runFfmpeg(ffmpeg, args, logDir.resolve("narration_concat.log"), "narration concat");
        return narrationWav;
    }

    private void generateSilence(double seconds, Path outWav, Path logDir, String ffmpeg) throws Exception {
        List<String> args = List.of(
                "-y", "-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono",
                "-t", String.valueOf(Math.max(seconds, 0.01)),
                "-c:a", "pcm_s16le",
                outWav.toAbsolutePath().toString());
        runFfmpeg(ffmpeg, args, logDir.resolve(outWav.getFileName() + ".log"), "silence generation");
    }

    // ------------------------------------------------------------- video clips

    private List<Path> buildClips(RenderManifest manifest, Path clipsDir, Path logDir, String ffmpeg) throws Exception {
        int width = manifest.width();
        int height = manifest.height();
        int fps = manifest.fps();

        // Clip encoding is CPU-bound and embarrassingly parallel (one ffmpeg process per
        // clip). Half the cores keeps the machine responsive; each ffmpeg is itself
        // lightly multithreaded. Futures are collected in entry order so concat order holds.
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
        try {
            List<java.util.concurrent.Future<List<Path>>> futures = new ArrayList<>();
            for (RenderManifest.Entry entry : manifest.entries()) {
                double clipLength = entry.durationSeconds() + entry.gapAfterSeconds();
                futures.add(pool.submit(() -> {
                    if (entry.type() == SegmentType.BROLL) {
                        return buildBrollShots(entry, clipLength, width, height, fps, clipsDir, logDir, ffmpeg);
                    }
                    Path clipOut = clipsDir.resolve(String.format("seg-%03d.mp4", entry.segmentIndex()));
                    buildGraphicClip(entry, clipLength, width, height, fps, clipsDir, clipOut, logDir, ffmpeg);
                    return List.of(clipOut);
                }));
            }
            List<Path> clips = new ArrayList<>();
            for (java.util.concurrent.Future<List<Path>> future : futures) {
                clips.addAll(future.get());
            }
            return clips;
        } finally {
            pool.shutdown();
        }
    }

    /** Target seconds per sub-shot; each sub-shot prefers its own generated "moment" image. */
    private static final double SUB_SHOT_SECONDS = 2.5;

    /**
     * Crop/zoom presets rotated across sub-shots: {extra scale, x-fraction, y-fraction}.
     * Scale > 1 overscans the image so the crop shows a distinct region — a hard cut to a
     * different framing of the same art reads as a fresh visual without generating a new image.
     */
    private static final double[][] SHOT_PRESETS = {
            {1.00, 0.5, 0.5},
            {1.45, 0.15, 0.15},
            {1.45, 0.85, 0.5},
            {1.25, 0.5, 0.85},
    };

    /**
     * The primary image is seg-%03d.png; CompositeVisualService writes additional per-moment
     * images as seg-%03d-01.png, -02.png... next to it. Each sub-shot uses its own moment
     * image when one exists; crop-preset variety only kicks in when it doesn't.
     */
    private List<Path> momentImages(RenderManifest.Entry entry) {
        List<Path> images = new ArrayList<>();
        Path primary = Path.of(entry.imagePath());
        images.add(primary);
        String base = primary.getFileName().toString().replaceFirst("\\.png$", "");
        for (int k = 1; k <= 16; k++) {
            Path candidate = primary.resolveSibling(String.format("%s-%02d.png", base, k));
            try {
                if (Files.exists(candidate) && Files.size(candidate) > 0) {
                    images.add(candidate);
                }
            } catch (IOException ignored) {
                // unreadable candidate: skip
            }
        }
        return images;
    }

    private List<Path> buildBrollShots(RenderManifest.Entry entry, double clipLength, int width, int height,
                                       int fps, Path clipsDir, Path logDir, String ffmpeg) throws Exception {
        int shots = Math.max(1, (int) Math.round(clipLength / SUB_SHOT_SECONDS));
        double subDur = clipLength / shots;
        List<Path> moments = momentImages(entry);

        List<Path> outs = new ArrayList<>();
        for (int i = 0; i < shots; i++) {
            // Spread the available moment images across the sub-shots in order.
            Path image = moments.get(Math.min(i * moments.size() / shots, moments.size() - 1));
            // STATIC presentation (no Ken Burns / zoompan — it read as "weird animation"):
            // each sub-shot is a still frame, hard cut to the next. When consecutive
            // sub-shots would share an image, crop presets vary the framing so the cut
            // still reads as a new shot.
            boolean uniqueImage = moments.size() >= shots;
            double[] preset = uniqueImage
                    ? SHOT_PRESETS[0]
                    : SHOT_PRESETS[(entry.segmentIndex() + i) % SHOT_PRESETS.length];

            int scaledW = (int) Math.round(width * preset[0] / 2) * 2;
            int scaledH = (int) Math.round(height * preset[0] / 2) * 2;
            String filter = String.format(java.util.Locale.ROOT,
                    "scale=%d:%d:force_original_aspect_ratio=increase,"
                            + "crop=%d:%d:x='(in_w-out_w)*%.2f':y='(in_h-out_h)*%.2f'",
                    scaledW, scaledH, width, height, preset[1], preset[2]);

            Path clipOut = clipsDir.resolve(String.format("seg-%03d-%02d.mp4", entry.segmentIndex(), i));
            List<String> head = List.of(
                    "-y", "-loop", "1", "-i", image.toAbsolutePath().toString(),
                    "-vf", filter,
                    "-t", String.valueOf(subDur),
                    "-r", String.valueOf(fps));
            encodeWithFallback(ffmpeg, head, clipOut, logDir, "broll sub-shot");
            outs.add(clipOut);
        }
        return outs;
    }

    private void buildGraphicClip(RenderManifest.Entry entry, double clipLength, int width, int height, int fps,
                                  Path clipsDir, Path clipOut, Path logDir, String ffmpeg) throws Exception {
        Path textFile = clipsDir.resolve(String.format("seg-%03d-text.txt", entry.segmentIndex()));
        Files.write(textFile, wrapText(entry.graphicText(), WRAP_CHARS).getBytes(StandardCharsets.UTF_8));

        String drawtext = String.format(
                "drawtext=fontfile='%s':textfile='%s':fontcolor=white:fontsize=80:line_spacing=20:"
                        + "shadowcolor=black@0.7:shadowx=4:shadowy=4:"
                        + "x=(w-text_w)/2:y=(h-text_h)/2",
                escapeFilterPath(FONT_PATH), escapeFilterPath(textFile.toAbsolutePath().toString()));

        List<String> head;
        if (entry.imagePath() != null) {
            // Stat card over the surrounding imagery: scale/crop, heavy blur, darken, then text.
            String filter = String.format(
                    "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,"
                            + "gblur=sigma=14,eq=brightness=-0.25:saturation=0.6,%s",
                    width, height, width, height, drawtext);
            head = List.of(
                    "-y", "-loop", "1", "-i", entry.imagePath(),
                    "-vf", filter,
                    "-t", String.valueOf(clipLength),
                    "-r", String.valueOf(fps));
        } else {
            // No prior image to reuse (graphic before any broll) — plain dark card.
            head = List.of(
                    "-y", "-f", "lavfi",
                    "-i", String.format("color=c=0x101018:s=%dx%d:d=%s", width, height, clipLength),
                    "-vf", drawtext,
                    "-r", String.valueOf(fps));
        }
        encodeWithFallback(ffmpeg, head, clipOut, logDir, "graphic clip");
    }

    /** Video-encoder args for the configured codec ("h264_amf" GPU or "libx264" CPU). */
    private List<String> codecArgs(String codec) {
        if ("h264_amf".equals(codec) || "h264_nvenc".equals(codec)) {
            return List.of("-c:v", codec, "-quality", "speed", "-rc", "cqp", "-qp_i", "20",
                    "-qp_p", "22", "-pix_fmt", "yuv420p", "-an");
        }
        return List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                "-pix_fmt", "yuv420p", "-an");
    }

    /**
     * Encodes with the configured (possibly hardware) codec, falling back to libx264 when
     * the hardware encoder is unavailable — driver/ffmpeg-build differences make AMF/NVENC
     * failures environmental, never fatal.
     */
    private void encodeWithFallback(String ffmpeg, List<String> headArgs, Path clipOut,
                                    Path logDir, String description) throws Exception {
        String codec = props.render() != null && props.render().codec() != null
                && !props.render().codec().isBlank() ? props.render().codec() : "libx264";
        List<String> args = new ArrayList<>(headArgs);
        args.addAll(codecArgs(codec));
        args.add(clipOut.toAbsolutePath().toString());
        try {
            runFfmpeg(ffmpeg, args, logDir.resolve(clipOut.getFileName() + ".log"), description);
        } catch (Exception e) {
            if ("libx264".equals(codec)) {
                throw e;
            }
            log.warn("{}: {} failed, falling back to libx264", description, codec);
            List<String> cpuArgs = new ArrayList<>(headArgs);
            cpuArgs.addAll(codecArgs("libx264"));
            cpuArgs.add(clipOut.toAbsolutePath().toString());
            runFfmpeg(ffmpeg, cpuArgs, logDir.resolve(clipOut.getFileName() + ".log"),
                    description + " (cpu fallback)");
        }
    }

    /** Breaks text into lines of roughly maxChars, breaking on word boundaries. */
    private String wrapText(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] words = text.trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (line.length() > 0 && line.length() + 1 + word.length() > maxChars) {
                result.append(line).append("\n");
                line = new StringBuilder();
            }
            if (line.length() > 0) {
                line.append(" ");
            }
            line.append(word);
        }
        if (line.length() > 0) {
            result.append(line);
        }
        return result.toString();
    }

    // ------------------------------------------------------------- captions

    private static final int CAPTION_MAX_WORDS = 5;

    /**
     * Builds an .ass subtitle file with phrase-level captions. Word timing is estimated
     * proportionally within each segment's measured audio duration (no ASR needed): the
     * narration is split into chunks of up to {@value CAPTION_MAX_WORDS} words, and each
     * chunk gets a share of the segment duration proportional to its word count.
     */
    private Path buildCaptions(RenderManifest manifest, Path workDir) throws IOException {
        StringBuilder ass = new StringBuilder();
        ass.append("[Script Info]\n")
                .append("ScriptType: v4.00+\n")
                .append("PlayResX: ").append(manifest.width()).append("\n")
                .append("PlayResY: ").append(manifest.height()).append("\n")
                .append("WrapStyle: 2\n\n")
                .append("[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, OutlineColour, BackColour, "
                        + "Bold, Italic, Outline, Shadow, Alignment, MarginL, MarginR, MarginV\n")
                .append("Style: Caption,Arial,62,&H00FFFFFF,&H00000000,&H80000000,"
                        + "-1,0,4,0,2,60,60,96\n\n")
                .append("[Events]\n")
                .append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");

        double t = 0.0;
        for (RenderManifest.Entry entry : manifest.entries()) {
            String narration = entry.narration();
            if (narration != null && !narration.isBlank()) {
                String[] words = narration.trim().split("\\s+");
                List<String[]> chunks = new ArrayList<>();
                for (int i = 0; i < words.length; i += CAPTION_MAX_WORDS) {
                    chunks.add(java.util.Arrays.copyOfRange(words, i,
                            Math.min(i + CAPTION_MAX_WORDS, words.length)));
                }
                double perWord = entry.durationSeconds() / words.length;
                double chunkStart = t;
                for (String[] chunk : chunks) {
                    double chunkDur = perWord * chunk.length;
                    ass.append("Dialogue: 0,").append(assTime(chunkStart)).append(',')
                            .append(assTime(chunkStart + chunkDur)).append(",Caption,,0,0,0,,")
                            .append(String.join(" ", chunk).replace("\n", " ").replace("{", "(").replace("}", ")"))
                            .append('\n');
                    chunkStart += chunkDur;
                }
            }
            t += entry.durationSeconds() + entry.gapAfterSeconds();
        }

        Path assFile = workDir.resolve("captions.ass");
        Files.write(assFile, ass.toString().getBytes(StandardCharsets.UTF_8));
        return assFile;
    }

    private String assTime(double seconds) {
        int h = (int) (seconds / 3600);
        int m = (int) ((seconds % 3600) / 60);
        double s = seconds % 60;
        return String.format(java.util.Locale.ROOT, "%d:%02d:%05.2f", h, m, s);
    }

    // ------------------------------------------------------------- concat + mux

    private Path concatClips(List<Path> clips, Path workDir, Path logDir, String ffmpeg) throws Exception {
        List<String> lines = new ArrayList<>();
        for (Path clip : clips) {
            lines.add("file '" + escapeConcatPath(clip.toAbsolutePath().toString()) + "'");
        }
        Path listFile = workDir.resolve("clips_concat.txt");
        Files.write(listFile, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));

        Path videoMp4 = workDir.resolve("video.mp4");
        List<String> args = List.of(
                "-y", "-f", "concat", "-safe", "0",
                "-i", listFile.toAbsolutePath().toString(),
                "-c", "copy",
                videoMp4.toAbsolutePath().toString());
        runFfmpeg(ffmpeg, args, logDir.resolve("clips_concat.log"), "clip concat");
        return videoMp4;
    }

    private Path mux(Path videoMp4, Path narrationWav, Path captionsAss, Path finalDir,
                     Path logDir, String ffmpeg) throws Exception {
        Path finalMp4 = finalDir.resolve("final.mp4");
        String musicPath = props.musicPath();

        // Burning captions requires re-encoding the video stream (no more -c:v copy).
        String captionFilter = "ass='" + escapeFilterPath(captionsAss.toAbsolutePath().toString()) + "'";

        String codec = props.render() != null && props.render().codec() != null
                && !props.render().codec().isBlank() ? props.render().codec() : "libx264";
        try {
            muxWithCodec(videoMp4, narrationWav, captionFilter, finalMp4, musicPath, codec, logDir, ffmpeg);
        } catch (Exception e) {
            if ("libx264".equals(codec)) {
                throw e;
            }
            log.warn("final mux: {} failed, falling back to libx264", codec);
            muxWithCodec(videoMp4, narrationWav, captionFilter, finalMp4, musicPath, "libx264", logDir, ffmpeg);
        }
        return finalMp4;
    }

    private void muxWithCodec(Path videoMp4, Path narrationWav, String captionFilter, Path finalMp4,
                              String musicPath, String codec, Path logDir, String ffmpeg) throws Exception {
        List<String> videoCodec = "libx264".equals(codec)
                ? List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p")
                : List.of("-c:v", codec, "-quality", "speed", "-rc", "cqp", "-qp_i", "20",
                        "-qp_p", "22", "-pix_fmt", "yuv420p");

        List<String> args = new ArrayList<>();
        if (musicPath != null && !musicPath.isBlank()) {
            // Mix looped background music at -18dB under the narration before muxing.
            args.addAll(List.of(
                    "-y",
                    "-i", videoMp4.toAbsolutePath().toString(),
                    "-i", narrationWav.toAbsolutePath().toString(),
                    "-stream_loop", "-1", "-i", musicPath,
                    "-filter_complex",
                    "[0:v]" + captionFilter + "[vout];"
                            + "[2:a]volume=-18dB,aloop=loop=-1:size=2147483647[music];"
                            + "[1:a][music]amix=inputs=2:duration=first:dropout_transition=0[aout]",
                    "-map", "[vout]", "-map", "[aout]"));
        } else {
            args.addAll(List.of(
                    "-y",
                    "-i", videoMp4.toAbsolutePath().toString(),
                    "-i", narrationWav.toAbsolutePath().toString(),
                    "-vf", captionFilter));
        }
        args.addAll(videoCodec);
        args.addAll(List.of(
                "-c:a", "aac", "-b:a", "192k",
                "-shortest",
                finalMp4.toAbsolutePath().toString()));
        runFfmpeg(ffmpeg, args, logDir.resolve("mux.log"), "final mux");
    }

    // ------------------------------------------------------------- helpers

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
        log.debug("ffmpeg step '{}' ok: {}", description, command);
    }

    private String escapeConcatPath(String path) {
        return path.replace("\\", "/").replace("'", "'\\''");
    }

    private String escapeFilterPath(String path) {
        // Inside a filter graph the option parser splits on ':' even within single
        // quotes, so the Windows drive colon must be escaped (C\:/...).
        return path.replace("\\", "/").replace(":", "\\:");
    }
}
