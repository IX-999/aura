package com.agent.video.model;

/**
 * One unit of the storyboard: a run of narration covered by a single visual.
 *
 * @param index        0-based position in the storyboard
 * @param section      the [SECTION: ...] this segment belongs to (may be "")
 * @param type         BROLL (generated image) or GRAPHIC (text card)
 * @param narration    plain narration text, ALL bracketed markers stripped
 * @param visualPrompt the B-ROLL image prompt, or the GRAPHIC card text
 * @param pauseAfter   true if a [PAUSE] follows this segment
 * @param twist        true if this segment carries the [TWIST]
 */
public record Segment(
        int index,
        String section,
        SegmentType type,
        String narration,
        String visualPrompt,
        boolean pauseAfter,
        boolean twist) {
}
