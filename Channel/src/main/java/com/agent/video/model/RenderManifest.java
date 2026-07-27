package com.agent.video.model;

import java.util.List;

/**
 * The single source of truth for rendering. Both the video clips and the
 * narration audio track are derived from these entries only, which is what
 * keeps them in sync.
 */
public record RenderManifest(String runId, String topic, int width, int height, int fps,
                             List<Entry> entries) {

    /**
     * @param segmentIndex    matching {@link Segment#index()}
     * @param type            BROLL or GRAPHIC
     * @param imagePath       absolute path to the PNG (BROLL only, else null)
     * @param graphicText     text card content (GRAPHIC only, else null)
     * @param narration       the spoken text of this segment (drives burned-in captions)
     * @param audioPath       absolute path to this segment's narration WAV
     * @param durationSeconds narration audio duration
     * @param gapAfterSeconds silence after this segment (pause or inter-segment gap);
     *                        the video clip for this entry lasts duration + gap
     */
    public record Entry(int segmentIndex, SegmentType type, String imagePath,
                        String graphicText, String narration, String audioPath,
                        double durationSeconds, double gapAfterSeconds) {
    }
}
