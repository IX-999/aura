package com.agent.video;

import java.nio.file.Path;

/**
 * Strategy for producing one 16:9 still from a scene prompt. Implementations:
 * Gemini via Vertex (paid, {@code ImagenVisualService}) and Pollinations.ai
 * (free, {@code PollinationsImageGenerator}). Selected per run by
 * {@code video.image.source}.
 */
public interface ImageGenerator {

    /** Generates one image for the prompt and writes it to the exact output path. */
    Path generate(String prompt, Path out) throws Exception;
}
