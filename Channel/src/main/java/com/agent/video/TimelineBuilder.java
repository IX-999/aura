package com.agent.video;

import com.agent.video.model.NarrationClip;
import com.agent.video.model.RenderManifest;
import com.agent.video.model.Storyboard;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Aligns narration durations with visuals into a render manifest. */
public interface TimelineBuilder {
    RenderManifest build(String runId, Storyboard storyboard,
                         List<NarrationClip> clips, Map<Integer, Path> images);
}
