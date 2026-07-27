package com.agent.video;

import com.agent.video.model.RenderManifest;

import java.nio.file.Path;

/** ffmpeg assembly: Ken Burns clips + graphic cards + narration track → final.mp4. */
public interface VideoRenderer {
    Path render(RenderManifest manifest, Path workDir) throws Exception;
}
