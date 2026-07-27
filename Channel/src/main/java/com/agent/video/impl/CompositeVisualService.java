package com.agent.video.impl;

import com.agent.video.VideoPipelineProperties;
import com.agent.video.VisualService;
import com.agent.video.model.Segment;
import com.agent.video.model.SegmentType;
import com.agent.video.model.Storyboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The only VisualService bean Spring injects. Delegates to Imagen and/or Wikimedia
 * per {@code video.image.source} ("imagen" | "wikimedia" | "hybrid"). See docs/VIDEO_PIPELINE.md.
 */
@Component
@Primary
public class CompositeVisualService implements VisualService {

    private static final Logger log = LoggerFactory.getLogger(CompositeVisualService.class);

    private final VideoPipelineProperties props;
    private final ImagenVisualService imagen;
    private final PollinationsImageGenerator pollinations;
    private final WikimediaSearch wikimedia;
    private final SdxlEndpointVisualService sdxl;

    public CompositeVisualService(VideoPipelineProperties props,
                                  @Value("${google.cloud.project-id}") String projectId,
                                  @Value("${google.cloud.location}") String location) {
        this.props = props;
        this.imagen = new ImagenVisualService(props, projectId, location);
        this.pollinations = new PollinationsImageGenerator(props);
        this.wikimedia = new WikimediaSearch();
        this.sdxl = buildSdxl(props);
    }

    private static SdxlEndpointVisualService buildSdxl(VideoPipelineProperties props) {
        String endpoint = props.image() != null ? props.image().sdxlEndpoint() : null;
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        try {
            return new SdxlEndpointVisualService(endpoint.trim());
        } catch (Exception e) {
            LoggerFactory.getLogger(CompositeVisualService.class)
                    .warn("SDXL endpoint service unavailable ({}); gemini fallback only", e.getMessage());
            return null;
        }
    }

    /**
     * Every image prompt carries the run's theme so the image model composes for the
     * story's world and its alternate-life tension, not just the single sentence it gets.
     */
    private static String themePrefix(Storyboard storyboard) {
        String topic = storyboard.topic() == null ? "" : storyboard.topic().trim();
        if (topic.isEmpty()) {
            return "";
        }
        String styleNotes = storyboard.styleNotes() == null ? "" : storyboard.styleNotes();
        return "Story: \"" + topic + "\" — a gripping second-person story; the viewer is "
                + "living this alternate life. " + styleNotes
                + "Compose the most tension-filled, thrilling version of this moment: ";
    }

    @Override
    public Map<Integer, Path> generateImages(Storyboard storyboard, Path imageDir) throws Exception {
        String source = props.image() != null && props.image().source() != null
                ? props.image().source() : "hybrid";
        int concurrency = props.image() != null && props.image().concurrency() != null
                ? Math.max(1, props.image().concurrency()) : 4;
        // ANONYMOUS Pollinations rate-limits per IP (~1 req/5s) and penalizes floods:
        // 8 workers measured SLOWER (2/min, heavy retries) than a polite trickle.
        // Authenticated (token) traffic is credit-metered, not IP-throttled — full pool.
        boolean pollinationsAnon = "pollinations".equals(props.image() != null ? props.image().source() : "")
                && (props.image().pollinationsToken() == null || props.image().pollinationsToken().isBlank());
        if (pollinationsAnon) {
            concurrency = Math.min(concurrency, 2);
        }

        List<Segment> brolls = storyboard.segments().stream()
                .filter(s -> s.type() == SegmentType.BROLL)
                .toList();

        // Bounded parallelism: the pool size is the pacing mechanism (no explicit sleeps);
        // per-request 429s are absorbed by ImagenVisualService's backoff retry.
        Map<Integer, Path> generated = new ConcurrentHashMap<>();
        String theme = themePrefix(storyboard);

        // Hero shots — the first segment of each section (level) — get the stronger,
        // slower gptimage model: better prompt adherence and legible in-image text where
        // it matters most (~8-10 images/video, ~+$0.25); flux carries the bulk.
        java.util.Set<Integer> heroIndexes = new java.util.HashSet<>();
        String lastSection = null;
        for (Segment s : brolls) {
            if (s.section() != null && !s.section().equals(lastSection)) {
                heroIndexes.add(s.index());
                lastSection = s.section();
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Segment segment : brolls) {
                boolean hero = heroIndexes.contains(segment.index());
                futures.add(pool.submit(() -> {
                    Path image = generateOne(segment, theme, imageDir, source, hero);
                    if (image != null) {
                        generated.put(segment.index(), image);
                    }
                }));
                // Extra "moment" images so the renderer can cut to genuinely new art every
                // ~2.5s: one image per ~7.5 narration words (~2.5s at ~180 wpm). The primary
                // image above is moment 1; these are 2..n. Failures are non-fatal — the
                // renderer just cuts between however many images exist.
                int moments = momentCount(segment.narration());
                for (int k = 1; k < moments; k++) {
                    final int momentIndex = k;
                    final int totalMoments = moments;
                    futures.add(pool.submit(() -> {
                        Path out = imageDir.resolve(String.format("seg-%03d-%02d.png",
                                segment.index(), momentIndex));
                        try {
                            if (java.nio.file.Files.exists(out) && java.nio.file.Files.size(out) > 0) {
                                return; // resume support
                            }
                            String prompt = theme + segment.visualPrompt()
                                    + " — moment " + (momentIndex + 1) + " of " + totalMoments
                                    + " in this scene: the action a few seconds later; choose a fresh "
                                    + "camera angle and composition that advances the moment.";
                            if ("pollinations".equals(source)) {
                                pollinations.generate(prompt, out);
                            } else {
                                imagen.generate(prompt, out);
                            }
                        } catch (Exception e) {
                            log.warn("segment {} moment {}: generation failed: {}",
                                    segment.index(), momentIndex, e.getMessage());
                        }
                    }));
                }
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }

        // Fill any per-segment failures with the nearest previous successful image.
        Map<Integer, Path> images = new HashMap<>();
        Path lastSuccessful = null;
        for (Segment segment : brolls) {
            Path image = generated.get(segment.index());
            if (image == null) {
                if (lastSuccessful == null) {
                    throw new IllegalStateException(
                            "No visual could be produced for segment " + segment.index()
                                    + " and no previous image to fall back to. "
                                    + "Check that the Vertex AI API is enabled for the project "
                                    + "and/or Wikimedia is reachable.");
                }
                log.warn("segment {}: all visual sources failed, reusing previous image {}",
                        segment.index(), lastSuccessful);
                image = lastSuccessful;
            } else {
                lastSuccessful = image;
            }
            images.put(segment.index(), image);
        }

        return images;
    }

    /** Images per segment: one per ~7.5 narration words (~2.5s at ~180 wpm), capped at 8. */
    private static int momentCount(String narration) {
        if (narration == null || narration.isBlank()) {
            return 1;
        }
        int words = narration.trim().split("\\s+").length;
        return Math.max(1, Math.min(8, (int) Math.round(words / 7.5)));
    }

    /** Returns the image path, or null if every configured source failed for this segment. */
    private Path generateOne(Segment segment, String theme, Path imageDir, String source, boolean hero) {
        // Resume support: if a previous (crashed/killed) run's image was copied into this
        // run's imageDir, reuse it instead of paying to regenerate.
        Path existing = imageDir.resolve(String.format("seg-%03d.png", segment.index()));
        try {
            if (java.nio.file.Files.exists(existing) && java.nio.file.Files.size(existing) > 0) {
                log.info("segment {}: reusing existing image {}", segment.index(), existing);
                return existing;
            }
        } catch (Exception ignored) {
            // fall through to generation
        }

        if ("pollinations".equals(source)) {
            // Tier 1+2: authenticated host, then the legacy anonymous host (separate
            // infrastructure) — both free, alternated inside the generator's retry ladder.
            try {
                return pollinations.generate(theme + segment.visualPrompt(), existing,
                        hero ? "gptimage" : null);
            } catch (Exception e) {
                log.warn("segment {}: Pollinations failed after retries (both hosts): {}",
                        segment.index(), e.getMessage());
            }
            // Tier 3: paid Gemini, only when explicitly enabled. Logged at WARN every time
            // so a Pollinations outage can never quietly run up a GCP bill unnoticed.
            boolean geminiFallback = props.image() == null || props.image().geminiFallback() == null
                    || props.image().geminiFallback();
            if (geminiFallback) {
                try {
                    log.warn("segment {}: FALLING BACK TO PAID GEMINI (~$0.04) — Pollinations unavailable",
                            segment.index());
                    return imagen.generate(theme + segment.visualPrompt(), existing);
                } catch (Exception e) {
                    log.warn("segment {}: Gemini fallback also failed: {}", segment.index(), e.getMessage());
                }
            }
            return null;
        }

        if ("sdxl".equals(source) && sdxl != null) {
            // Two attempts on the GPU endpoint, then fall through to gemini as backstop.
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String suffix = props.image() != null && props.image().styleSuffix() != null
                            ? props.image().styleSuffix() : "";
                    return sdxl.generate(segment.visualPrompt(), suffix, segment.index(), imageDir);
                } catch (Exception e) {
                    log.warn("segment {}: SDXL endpoint attempt {} failed: {}",
                            segment.index(), attempt + 1, e.getMessage());
                }
            }
        }

        boolean tryWikimedia = "wikimedia".equals(source) || "hybrid".equals(source);
        boolean tryImagen = "imagen".equals(source) || "hybrid".equals(source) || "wikimedia".equals(source)
                || "sdxl".equals(source);

        if (tryWikimedia) {
            try {
                Path result = wikimedia.search(segment.visualPrompt(), segment.index(), imageDir);
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                log.warn("segment {}: Wikimedia search failed: {}", segment.index(), e.getMessage());
            }
        }

        if (tryImagen) {
            try {
                return imagen.generate(segment.visualPrompt(), segment.index(), imageDir);
            } catch (Exception e) {
                log.warn("segment {}: Imagen generation failed: {}", segment.index(), e.getMessage());
            }
        }

        return null;
    }
}
