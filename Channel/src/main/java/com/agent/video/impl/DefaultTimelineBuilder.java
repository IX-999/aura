package com.agent.video.impl;

import com.agent.video.TimelineBuilder;
import com.agent.video.VideoPipelineProperties;
import com.agent.video.model.NarrationClip;
import com.agent.video.model.RenderManifest;
import com.agent.video.model.Segment;
import com.agent.video.model.SegmentType;
import com.agent.video.model.Storyboard;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Aligns narration durations with visuals into a render manifest. See docs/VIDEO_PIPELINE.md. */
@Component
public class DefaultTimelineBuilder implements TimelineBuilder {

    private final VideoPipelineProperties props;

    public DefaultTimelineBuilder(VideoPipelineProperties props) {
        this.props = props;
    }

    @Override
    public RenderManifest build(String runId, Storyboard storyboard,
                                List<NarrationClip> clips, Map<Integer, Path> images) {
        Map<Integer, NarrationClip> clipsByIndex = new HashMap<>();
        for (NarrationClip clip : clips) {
            clipsByIndex.put(clip.segmentIndex(), clip);
        }

        List<RenderManifest.Entry> entries = new ArrayList<>();
        Path lastImage = null;

        for (Segment segment : storyboard.segments()) {
            NarrationClip clip = clipsByIndex.get(segment.index());
            if (clip == null) {
                throw new IllegalStateException("No narration clip for segment " + segment.index());
            }

            String imagePath = null;
            String graphicText = null;
            if (segment.type() == SegmentType.BROLL) {
                Path image = images.get(segment.index());
                if (image == null) {
                    image = lastImage; // nearest previous entry's image
                }
                if (image != null) {
                    imagePath = image.toAbsolutePath().toString();
                    lastImage = image;
                }
            } else {
                // Models sometimes prefix the on-screen text with a "graphic:" label — strip it.
                graphicText = segment.visualPrompt().replaceFirst("(?i)^\\s*graphic\\s*[:\\-]\\s*", "");
                // Graphics render as a stat card over the surrounding imagery (blurred/darkened
                // by the renderer) instead of a bare text-on-black slide.
                if (lastImage != null) {
                    imagePath = lastImage.toAbsolutePath().toString();
                }
            }

            double gapAfterSeconds = segment.pauseAfter()
                    ? props.render().pauseGap()
                    : props.render().interSegmentGap();

            entries.add(new RenderManifest.Entry(
                    segment.index(),
                    segment.type(),
                    imagePath,
                    graphicText,
                    segment.narration(),
                    clip.wavFile().toAbsolutePath().toString(),
                    clip.durationSeconds(),
                    gapAfterSeconds));
        }

        return new RenderManifest(runId, storyboard.topic(),
                props.render().width(), props.render().height(), props.render().fps(), entries);
    }
}
