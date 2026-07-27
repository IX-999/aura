package com.agent.video;

import com.agent.video.model.Storyboard;

/** Parses a marker-annotated script into a storyboard. See docs/VIDEO_PIPELINE.md. */
public interface ScriptParser {
    Storyboard parse(String topic, String scriptText);
}
