package com.agent.video.model;

import java.util.List;

/**
 * @param styleNotes character-design sheet extracted from the script's storyBible
 *                   ("You: a tired man in a grey hoodie; Sal: ..."). Appended to every
 *                   image prompt so cast and art style stay consistent across frames —
 *                   the image model has no memory between calls, so consistency must
 *                   travel inside each prompt. Empty when the script has no bible.
 */
public record Storyboard(String topic, List<Segment> segments, String styleNotes) {

    public Storyboard(String topic, List<Segment> segments) {
        this(topic, segments, "");
    }
}
