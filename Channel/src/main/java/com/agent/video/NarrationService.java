package com.agent.video;

import com.agent.video.model.NarrationClip;
import com.agent.video.model.Storyboard;

import java.nio.file.Path;
import java.util.List;

/** Synthesizes one narration WAV per segment (Cloud TTS, Chirp 3 HD). */
public interface NarrationService {
    List<NarrationClip> synthesize(Storyboard storyboard, Path audioDir) throws Exception;
}
