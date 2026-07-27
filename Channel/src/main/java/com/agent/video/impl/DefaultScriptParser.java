package com.agent.video.impl;

import com.agent.video.ScriptParser;
import com.agent.video.model.Segment;
import com.agent.video.model.SegmentType;
import com.agent.video.model.Storyboard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a script into a storyboard, trying JSON first, then falling back to marker format.
 *
 * <p>
 * <strong>PRIMARY PATH — JSON:</strong>
 * Expects: {@code {topic, title, sections:[{name, segments:[{type:"broll"|"graphic", visual, narration, pause?, twist?}]}]}}
 * If the input (trimmed, with ```json fence stripped) starts with '{' and parses as valid JSON,
 * maps it directly to Segments. Flattens sections' segments in order; skips segments with blank
 * narration or visual. Unknown JSON fields ignored; missing booleans default to false.
 * </p>
 *
 * <p>
 * <strong>FALLBACK PATH — Legacy markers:</strong>
 * Markers: [SECTION: name], [B-ROLL: prompt], [GRAPHIC: text], [TWIST], [PAUSE].
 * SECTION/B-ROLL/GRAPHIC appear on their own lines; TWIST and PAUSE may appear
 * on their own line or inline mid-paragraph.
 * </p>
 * <p>
 * Parsing rules:
 * - A [B-ROLL] or [GRAPHIC] marker starts a new Segment.
 * - Consecutive visual markers with NO narration between them collapse to the LAST one.
 * - ALL bracketed markers (any [...] token) are stripped from narration text.
 * - [PAUSE] sets pauseAfter=true on the segment containing the narration before it;
 *   if narration continues after the pause within the same visual, split into a second segment.
 * - [TWIST] sets twist=true on the segment it appears in.
 * - [SECTION: name] updates the current section name for subsequent segments.
 * - Narration appearing before any visual marker gets a BROLL segment with visualPrompt:
 *   "Cinematic establishing shot for a documentary about {topic}".
 * - Segments with blank narration are dropped.
 * </p>
 */
@Component
public class DefaultScriptParser implements ScriptParser {

    private static final Pattern SECTION_PATTERN = Pattern.compile("^\\[SECTION:\\s*(.+?)\\]\\s*$");
    private static final Pattern BROLL_PATTERN = Pattern.compile("^\\[B-ROLL:\\s*(.+?)\\]\\s*$");
    private static final Pattern GRAPHIC_PATTERN = Pattern.compile("^\\[GRAPHIC:\\s*(.+?)\\]\\s*$");
    private static final Pattern MARKER_PATTERN = Pattern.compile("\\[.*?\\]");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Storyboard parse(String topic, String scriptText) {
        String trimmed = scriptText.trim();

        // Try JSON path first
        String jsonInput = stripJsonFence(trimmed);
        if (jsonInput.startsWith("{")) {
            try {
                List<Segment> jsonSegments = parseJson(topic, jsonInput);
                if (jsonSegments != null) {
                    return new Storyboard(topic, splitLongSegments(jsonSegments),
                            extractStyleNotes(jsonInput));
                }
            } catch (Exception e) {
                // Malformed JSON (models occasionally emit a stray character). Salvage the
                // visual/narration pairs by regex rather than marker-parsing JSON text —
                // the marker parser turns a JSON blob into one garbage mega-segment.
                List<Segment> salvaged = salvageJsonSegments(jsonInput);
                if (salvaged.size() >= 3) {
                    return new Storyboard(topic, splitLongSegments(salvaged),
                            extractStyleNotes(jsonInput));
                }
                throw new IllegalArgumentException(
                        "Script looks like JSON but failed to parse and could not be salvaged ("
                                + salvaged.size() + " segments recovered): " + e.getMessage(), e);
            }
        }

        // Fallback: marker parser (legacy marker-format scripts only)
        return parseMarkers(topic, scriptText);
    }

    /** Above this many narration words, a segment is split for visual pacing (~18s max per image). */
    private static final int MAX_SEGMENT_WORDS = 55;
    /** A split chunk aims for this many words (~12s of speech). */
    private static final int TARGET_CHUNK_WORDS = 45;
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");

    /**
     * Guarantees visual pacing mechanically: any segment over {@value MAX_SEGMENT_WORDS} narration
     * words is split at sentence boundaries into ~{@value TARGET_CHUNK_WORDS}-word chunks. The
     * first chunk keeps the original visual; later chunks get a varied-angle version of it so each
     * chunk still renders a fresh image. Indices are reassigned sequentially.
     */
    List<Segment> splitLongSegments(List<Segment> segments) {
        List<Segment> result = new ArrayList<>();
        for (Segment seg : segments) {
            String[] words = seg.narration().trim().split("\\s+");
            if (words.length <= MAX_SEGMENT_WORDS) {
                result.add(seg);
                continue;
            }

            String[] sentences = SENTENCE_SPLIT.split(seg.narration().trim());
            List<String> chunks = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int currentWords = 0;
            for (String sentence : sentences) {
                int sentenceWords = sentence.trim().split("\\s+").length;
                if (currentWords > 0 && currentWords + sentenceWords > TARGET_CHUNK_WORDS + 10) {
                    chunks.add(current.toString());
                    current = new StringBuilder();
                    currentWords = 0;
                }
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(sentence.trim());
                currentWords += sentenceWords;
            }
            if (current.length() > 0) {
                chunks.add(current.toString());
            }

            for (int i = 0; i < chunks.size(); i++) {
                String visual = i == 0
                        ? seg.visualPrompt()
                        : "A different camera angle on the same scene, moments later: " + seg.visualPrompt();
                boolean last = i == chunks.size() - 1;
                result.add(new Segment(-1, seg.section(), seg.type(), chunks.get(i), visual,
                        last && seg.pauseAfter(), i == 0 && seg.twist()));
            }
        }

        List<Segment> reindexed = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            Segment s = result.get(i);
            reindexed.add(new Segment(i, s.section(), s.type(), s.narration(), s.visualPrompt(),
                    s.pauseAfter(), s.twist()));
        }
        return reindexed;
    }

    private static final Pattern CHARACTER_LOOK = Pattern.compile(
            "\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"[^{}]*?\"look\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.DOTALL);

    /**
     * Builds a character-design sheet from the script's storyBible (name + look pairs).
     * Works on the raw JSON text so it survives scripts that need regex salvage too.
     */
    String extractStyleNotes(String scriptJson) {
        Matcher m = CHARACTER_LOOK.matcher(scriptJson);
        List<String> entries = new ArrayList<>();
        while (m.find() && entries.size() < 8) {
            String name = unescapeJson(m.group(1)).trim();
            String look = unescapeJson(m.group(2)).trim();
            if (!name.isEmpty() && !look.isEmpty()) {
                entries.add(name + ": " + look);
            }
        }
        if (entries.isEmpty()) {
            return "";
        }
        return "Character designs — draw each character EXACTLY like this in every frame ("
                + String.join("; ", entries) + "). Keep one consistent art style across all frames. ";
    }

    private static final Pattern SALVAGE_SECTION = Pattern.compile("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern SALVAGE_SEGMENT = Pattern.compile(
            "\"type\"\\s*:\\s*\"(broll|graphic)\"[^\"]*\"visual\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"[^\"]*"
                    + "\"narration\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Regex-recovers segments from JSON-shaped text that Jackson rejected. */
    private List<Segment> salvageJsonSegments(String text) {
        List<Segment> segments = new ArrayList<>();
        // Track the most recent section name preceding each segment match.
        List<int[]> sectionStarts = new ArrayList<>();
        List<String> sectionNames = new ArrayList<>();
        Matcher sm = SALVAGE_SECTION.matcher(text);
        while (sm.find()) {
            sectionStarts.add(new int[]{sm.start()});
            sectionNames.add(unescapeJson(sm.group(1)));
        }

        Matcher m = SALVAGE_SEGMENT.matcher(text);
        int index = 0;
        while (m.find()) {
            String section = "";
            for (int i = 0; i < sectionStarts.size(); i++) {
                if (sectionStarts.get(i)[0] < m.start()) {
                    section = sectionNames.get(i);
                }
            }
            String visual = unescapeJson(m.group(2)).trim()
                    .replaceFirst("(?i)^\\s*(broll|b-roll|graphic)\\s*[:\\-]\\s*", "");
            String narration = unescapeJson(m.group(3)).trim();
            if (visual.isEmpty() || narration.isEmpty()) {
                continue;
            }
            segments.add(new Segment(index++, section, SegmentType.BROLL, narration, visual, false, false));
        }
        return segments;
    }

    private String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\n", " ").replace("\\t", " ").replace("\\\\", "\\");
    }

    /**
     * Strip ```json ... ``` fence if present.
     */
    private String stripJsonFence(String text) {
        if (text.startsWith("```json")) {
            text = text.substring(7).trim();
        }
        if (text.startsWith("```")) {
            text = text.substring(3).trim();
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3).trim();
        }
        return text;
    }

    /**
     * Parse JSON schema: {topic, title, sections:[{name, segments:[{type, visual, narration, pause?, twist?}]}]}
     */
    private List<Segment> parseJson(String topic, String jsonText) throws Exception {
        JsonNode root = MAPPER.readTree(jsonText);

        List<Segment> segments = new ArrayList<>();
        JsonNode sectionsNode = root.get("sections");
        if (sectionsNode == null || !sectionsNode.isArray()) {
            return null;
        }

        int index = 0;
        for (JsonNode sectionNode : sectionsNode) {
            String sectionName = sectionNode.has("name") ? sectionNode.get("name").asText("") : "";
            JsonNode segmentsNode = sectionNode.get("segments");

            if (segmentsNode == null || !segmentsNode.isArray()) {
                continue;
            }

            for (JsonNode segNode : segmentsNode) {
                String typeStr = segNode.has("type") ? segNode.get("type").asText("").toLowerCase() : "";
                String visual = segNode.has("visual") ? segNode.get("visual").asText("") : "";
                String narration = segNode.has("narration") ? segNode.get("narration").asText("") : "";
                boolean pause = segNode.has("pause") ? segNode.get("pause").asBoolean(false) : false;
                boolean twist = segNode.has("twist") ? segNode.get("twist").asBoolean(false) : false;

                // Skip segments with blank visual or narration
                if (visual.isBlank() || narration.isBlank()) {
                    continue;
                }

                // Models sometimes echo the type as a prefix inside the visual field.
                visual = visual.replaceFirst("(?i)^\\s*(broll|b-roll|graphic)\\s*[:\\-]\\s*", "");

                // JSON scripts are images-only: any stray "graphic" is coerced to BROLL so a
                // text-on-background card can never render. (Marker scripts keep GRAPHIC.)
                segments.add(new Segment(index++, sectionName, SegmentType.BROLL, narration, visual, pause, twist));
            }
        }

        return segments.isEmpty() ? null : segments;
    }

    /**
     * Parse legacy marker format.
     */
    private Storyboard parseMarkers(String topic, String scriptText) {
        List<Segment> segments = new ArrayList<>();
        String[] lines = scriptText.split("\n");

        String currentSection = "";
        String currentSegmentSection = ""; // section captured when visual marker is encountered
        SegmentType currentType = null;
        String currentVisualPrompt = null;
        StringBuilder currentNarration = new StringBuilder();
        boolean hasSeenVisual = false;

        for (String line : lines) {
            // Check for section marker
            Matcher sectionMatcher = SECTION_PATTERN.matcher(line);
            if (sectionMatcher.matches()) {
                currentSection = sectionMatcher.group(1);
                continue;
            }

            // Check for B-ROLL marker
            Matcher brollMatcher = BROLL_PATTERN.matcher(line);
            if (brollMatcher.matches()) {
                // Save current segment if has narration and last visual wasn't just replaced
                if (currentNarration.length() > 0 && currentType != null) {
                    saveSegment(segments, currentSegmentSection, currentType, currentNarration,
                            currentVisualPrompt, false, false);
                    currentNarration = new StringBuilder();
                }
                currentType = SegmentType.BROLL;
                currentVisualPrompt = brollMatcher.group(1);
                currentSegmentSection = currentSection; // capture section at this point
                hasSeenVisual = true;
                continue;
            }

            // Check for GRAPHIC marker
            Matcher graphicMatcher = GRAPHIC_PATTERN.matcher(line);
            if (graphicMatcher.matches()) {
                // Save current segment if has narration and last visual wasn't just replaced
                if (currentNarration.length() > 0 && currentType != null) {
                    saveSegment(segments, currentSegmentSection, currentType, currentNarration,
                            currentVisualPrompt, false, false);
                    currentNarration = new StringBuilder();
                }
                currentType = SegmentType.GRAPHIC;
                currentVisualPrompt = graphicMatcher.group(1);
                currentSegmentSection = currentSection; // capture section at this point
                hasSeenVisual = true;
                continue;
            }

            // It's a narration line (may contain inline [PAUSE] and [TWIST])
            if (!line.trim().isEmpty() || currentNarration.length() > 0) {
                // If we haven't seen a visual yet and this is the first narration,
                // create a default BROLL segment
                if (!hasSeenVisual && currentType == null && !line.trim().isEmpty()) {
                    currentType = SegmentType.BROLL;
                    currentVisualPrompt = "Cinematic establishing shot for a documentary about " + topic;
                    currentSegmentSection = currentSection; // capture section at this point
                    hasSeenVisual = true;
                }

                // Add narration line
                if (currentNarration.length() > 0) {
                    currentNarration.append(" ");
                }
                currentNarration.append(line);
            }
        }

        // Save the last segment
        if (currentNarration.length() > 0 && currentType != null) {
            saveSegment(segments, currentSegmentSection, currentType, currentNarration,
                    currentVisualPrompt, false, false);
        }

        // Process segments: strip markers, handle pause/twist, normalize whitespace, drop blanks
        List<Segment> processedSegments = processSegments(segments);

        return new Storyboard(topic, processedSegments);
    }

    private void saveSegment(List<Segment> segments, String section, SegmentType type,
                            StringBuilder narration, String visualPrompt,
                            boolean pauseAfter, boolean twist) {
        String narrationStr = narration.toString().trim();
        if (!narrationStr.isEmpty()) {
            segments.add(new Segment(
                    -1, // index will be set during post-processing
                    section,
                    type,
                    narrationStr,
                    visualPrompt,
                    pauseAfter,
                    twist
            ));
        }
    }

    private List<Segment> processSegments(List<Segment> rawSegments) {
        List<Segment> result = new ArrayList<>();

        for (Segment seg : rawSegments) {
            // Handle inline [PAUSE] split first, then process each part for markers
            if (seg.narration().contains("[PAUSE]")) {
                String[] parts = seg.narration().split("\\[PAUSE\\]", 2);
                String beforePause = stripMarkers(parts[0]).trim();
                String afterPause = parts.length > 1 ? stripMarkers(parts[1]).trim() : "";

                beforePause = normalizeWhitespace(beforePause);
                afterPause = normalizeWhitespace(afterPause);

                // Check for twist in each part
                String beforeOriginal = parts[0];
                String afterOriginal = parts.length > 1 ? parts[1] : "";
                boolean twistBefore = beforeOriginal.contains("[TWIST]");
                boolean twistAfter = afterOriginal.contains("[TWIST]");

                if (!beforePause.isEmpty()) {
                    result.add(new Segment(
                            -1,
                            seg.section(),
                            seg.type(),
                            beforePause,
                            seg.visualPrompt(),
                            true, // pauseAfter=true for segment before pause
                            twistBefore || seg.twist()
                    ));
                }

                if (!afterPause.isEmpty()) {
                    result.add(new Segment(
                            -1,
                            seg.section(),
                            seg.type(),
                            afterPause,
                            seg.visualPrompt(),
                            false, // pauseAfter=false for segment after pause
                            twistAfter
                    ));
                }
            } else {
                // No pause, process normally
                String narration = stripMarkers(seg.narration());
                boolean twist = seg.twist();

                // Check for inline [TWIST] in the original narration
                if (seg.narration().contains("[TWIST]")) {
                    twist = true;
                }

                // Normalize whitespace
                narration = normalizeWhitespace(narration);

                // Drop if blank after stripping
                if (narration.isEmpty()) {
                    continue;
                }

                result.add(new Segment(
                        -1,
                        seg.section(),
                        seg.type(),
                        narration,
                        seg.visualPrompt(),
                        seg.pauseAfter(),
                        twist
                ));
            }
        }

        // Assign indices
        List<Segment> indexedSegments = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            Segment seg = result.get(i);
            indexedSegments.add(new Segment(
                    i,
                    seg.section(),
                    seg.type(),
                    seg.narration(),
                    seg.visualPrompt(),
                    seg.pauseAfter(),
                    seg.twist()
            ));
        }

        return indexedSegments;
    }

    private String stripMarkers(String text) {
        return MARKER_PATTERN.matcher(text).replaceAll("");
    }

    private String normalizeWhitespace(String text) {
        // Collapse runs of whitespace (including newlines) to single spaces
        return text.replaceAll("\\s+", " ").trim();
    }
}
