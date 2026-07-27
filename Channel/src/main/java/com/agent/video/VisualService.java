package com.agent.video;

import com.agent.video.model.Storyboard;

import java.nio.file.Path;
import java.util.Map;

/**
 * Generates one 16:9 still per BROLL segment (Vertex AI Imagen).
 * Returns segmentIndex → PNG path. GRAPHIC segments get no image.
 * Per-image failures fall back to the previous successful image — never fail the run.
 */
public interface VisualService {
    Map<Integer, Path> generateImages(Storyboard storyboard, Path imageDir) throws Exception;
}
