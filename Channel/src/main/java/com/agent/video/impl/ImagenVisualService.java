package com.agent.video.impl;

import com.agent.video.VideoPipelineProperties;
import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.ImageConfig;
import com.google.genai.types.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates a single 16:9 still via Gemini image generation (com.google.genai SDK).
 *
 * NOTE: this project has no Vertex AI Imagen model access (imagen-4.0-generate-001,
 * imagen-3.0-generate-002, imagegeneration@006 all 404 on predict in us-central1, verified
 * live). gemini-2.5-flash-image via generateContent DOES work and is used instead
 * (~$0.04/image). See docs/VIDEO_PIPELINE.md.
 *
 * Not a Spring bean itself — wired directly by {@link CompositeVisualService}.
 */
public class ImagenVisualService implements com.agent.video.ImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(ImagenVisualService.class);

    private final VideoPipelineProperties props;
    /**
     * One shared client per Vertex region (thread-safe). Quota is per-region, so requests
     * are distributed across regions and retries rotate to the next region — effective
     * throughput scales with the number of configured locations.
     */
    private final List<Client> clients;
    private final List<String> locations;

    public ImagenVisualService(VideoPipelineProperties props, String projectId, String defaultLocation) {
        this.props = props;
        String configured = props.image() != null && props.image().locations() != null
                && !props.image().locations().isBlank()
                ? props.image().locations() : defaultLocation;
        this.locations = java.util.Arrays.stream(configured.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.clients = locations.stream()
                .map(loc -> Client.builder().vertexAI(true).project(projectId).location(loc).build())
                .toList();
    }

    /** Generates one image for the given prompt and writes it to imageDir/seg-%03d.png. */
    public Path generate(String prompt, int segmentIndex, Path imageDir) throws Exception {
        return generate(prompt, imageDir.resolve(String.format("seg-%03d.png", segmentIndex)));
    }

    /** Generates one image for the given prompt and writes it to the exact output path. */
    public Path generate(String prompt, Path out) throws Exception {
        VideoPipelineProperties.Image imageProps = props.image();
        String fullPrompt = "Generate a single 16:9 widescreen image. " + prompt + ", " + imageProps.styleSuffix();

        GenerateContentConfig config = GenerateContentConfig.builder()
                .responseModalities("TEXT", "IMAGE")
                .imageConfig(ImageConfig.builder().aspectRatio("16:9").build())
                // Dark narrative prompts (crime scenes, poverty, distress) trip default
                // safety filters and come back with no image; storyboard prompts are
                // already gated upstream by ContentPolicy.
                .safetySettings(com.agent.agent.StoryTellerAgent.relaxedSafetySettings())
                .build();

        int retryKey = Math.abs(out.getFileName().toString().hashCode());
        byte[] bytes = generateWithRetry(imageProps.model(), fullPrompt, config, retryKey);

        Files.write(out, bytes);
        log.info("Gemini image: {} ", out);
        return out;
    }

    /**
     * Retries with backoff, rotating to a different region on EVERY retry (each region has
     * its own rate-limit bucket, so a 429 in one region says nothing about the next).
     * A region where the model doesn't exist (404) is rotated past without backoff.
     */
    private byte[] generateWithRetry(String model, String prompt,
                                     GenerateContentConfig config, int segmentIndex) throws Exception {
        int[] backoffSeconds = {0, 10, 20, 45, 90, 150, 240};
        Exception last = null;
        for (int attempt = 0; attempt < backoffSeconds.length; attempt++) {
            int slot = (segmentIndex + attempt) % clients.size();
            if (backoffSeconds[attempt] > 0) {
                log.info("segment {}: retrying image generation in {}s via {} (attempt {}/{})",
                        segmentIndex, backoffSeconds[attempt], locations.get(slot),
                        attempt + 1, backoffSeconds.length);
                Thread.sleep(backoffSeconds[attempt] * 1000L);
            }
            try {
                GenerateContentResponse response =
                        clients.get(slot).models.generateContent(model, prompt, config);
                return extractImageBytes(response, segmentIndex);
            } catch (Exception e) {
                last = e;
                String msg = String.valueOf(e.getMessage());
                boolean regionMissing = msg.contains("404") || msg.contains("NOT_FOUND");
                boolean retryable = regionMissing || msg.contains("429")
                        || msg.contains("Resource exhausted") || msg.contains("503")
                        || msg.contains("returned no inline image data");
                if (!retryable) {
                    throw e;
                }
                if (regionMissing) {
                    log.warn("segment {}: model {} not available in {}, rotating region",
                            segmentIndex, model, locations.get(slot));
                }
            }
        }
        throw last;
    }

    private byte[] extractImageBytes(GenerateContentResponse response, int segmentIndex) {
        List<Candidate> candidates = response.candidates().orElse(null);
        if (candidates != null) {
            for (Candidate candidate : candidates) {
                Content content = candidate.content().orElse(null);
                if (content == null) {
                    continue;
                }
                List<Part> parts = content.parts().orElse(null);
                if (parts == null) {
                    continue;
                }
                for (Part part : parts) {
                    var inlineData = part.inlineData().orElse(null);
                    if (inlineData != null) {
                        byte[] data = inlineData.data().orElse(null);
                        if (data != null && data.length > 0) {
                            return data;
                        }
                    }
                }
            }
        }
        throw new IllegalStateException(
                "Gemini image generation returned no inline image data for segment " + segmentIndex
                        + " (model: " + props.image().model() + ")");
    }
}
