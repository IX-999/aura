package com.agent.video.model;

import java.nio.file.Path;

/**
 * A synthesized narration WAV for one segment.
 *
 * @param segmentIndex    matching {@link Segment#index()}
 * @param wavFile         LINEAR16 24kHz mono WAV on local disk
 * @param durationSeconds duration derived from the WAV data-chunk size
 */
public record NarrationClip(int segmentIndex, Path wavFile, double durationSeconds) {
}
