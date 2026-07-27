package com.agent.video;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All video-pipeline knobs, bound from application.properties (prefix "video"). */
@ConfigurationProperties(prefix = "video")
public record VideoPipelineProperties(
        String workDir,
        Gcs gcs,
        Tts tts,
        Image image,
        Render render,
        String ffmpegPath,
        String musicPath) {

    public record Gcs(boolean enabled, String bucket) {
    }

    /** voice: Chirp 3 HD voices are expressive/natural; "Puck" is the upbeat one. */
    public record Tts(String voice, String languageCode, double speakingRate) {
    }

    /**
     * source: "sdxl" (dedicated GPU endpoint, gemini fallback) | "imagen" | "wikimedia" | "hybrid".
     * concurrency: parallel image-generation requests (null -> 4).
     * locations: comma-separated Vertex regions to spread gemini requests across (quota is
     * per-region, so each extra region multiplies effective throughput).
     * sdxlEndpoint: full resource name of the SDXL-Lightning endpoint
     * ("projects/P/locations/L/endpoints/ID"); required when source=sdxl.
     */
    /**
     * geminiFallback: when the free Pollinations tiers are exhausted, generate the
     * remaining images via paid Vertex Gemini (~$0.04 each) instead of duplicating
     * frames. Null defaults to true; every use is logged at WARN.
     */
    public record Image(String source, String model, String styleSuffix, Integer concurrency,
                        String locations, String sdxlEndpoint, String pollinationsToken,
                        String pollinationsModel, Boolean geminiFallback) {
    }

    /** codec: "h264_amf" (AMD GPU), "h264_nvenc" (NVIDIA), or "libx264" (CPU, default). */
    public record Render(int width, int height, int fps,
                         double interSegmentGap, double pauseGap, String codec) {
    }
}
