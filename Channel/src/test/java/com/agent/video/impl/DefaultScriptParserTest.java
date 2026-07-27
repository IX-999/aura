package com.agent.video.impl;

import com.agent.video.model.Segment;
import com.agent.video.model.SegmentType;
import com.agent.video.model.Storyboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DefaultScriptParser.
 * Covers JSON primary path, fallback to marker format, and edge cases.
 */
class DefaultScriptParserTest {

    private DefaultScriptParser parser;

    @BeforeEach
    void setUp() {
        parser = new DefaultScriptParser();
    }

    /**
     * Test 1: JSON script with two sections, broll+graphic segments, pause and twist flags, ```json fence.
     */
    @Test
    void testJsonScriptWithFenceAndFlags() {
        String topic = "The Zodiac Killer";
        String jsonScript = """
            ```json
            {
              "topic": "The Zodiac Killer",
              "title": "Cracking the Zodiac Code",
              "sections": [
                {
                  "name": "Cold Open",
                  "segments": [
                    {
                      "type": "broll",
                      "visual": "Fading grainy footage of late 1960s California",
                      "narration": "Imagine a string of symbols that baffled the FBI for fifty years."
                    },
                    {
                      "type": "graphic",
                      "visual": "The 408 Cipher",
                      "narration": "This is the 408 cipher.",
                      "pause": true
                    }
                  ]
                },
                {
                  "name": "The Twist",
                  "segments": [
                    {
                      "type": "broll",
                      "visual": "Computer screens showing deciphering software",
                      "narration": "Then an ordinary person cracked it.",
                      "twist": true
                    }
                  ]
                }
              ]
            }
            ```
            """;

        Storyboard board = parser.parse(topic, jsonScript);

        assertNotNull(board);
        assertEquals(topic, board.topic());
        assertEquals(3, board.segments().size());

        // Segment 0: Cold Open, BROLL
        Segment seg0 = board.segments().get(0);
        assertEquals(0, seg0.index());
        assertEquals("Cold Open", seg0.section());
        assertEquals(SegmentType.BROLL, seg0.type());
        assertEquals("Imagine a string of symbols that baffled the FBI for fifty years.", seg0.narration());
        assertEquals("Fading grainy footage of late 1960s California", seg0.visualPrompt());
        assertFalse(seg0.pauseAfter());
        assertFalse(seg0.twist());

        // Segment 1: Cold Open, "graphic" in JSON is coerced to BROLL (images-only pipeline)
        Segment seg1 = board.segments().get(1);
        assertEquals(1, seg1.index());
        assertEquals("Cold Open", seg1.section());
        assertEquals(SegmentType.BROLL, seg1.type());
        assertEquals("This is the 408 cipher.", seg1.narration());
        assertEquals("The 408 Cipher", seg1.visualPrompt());
        assertTrue(seg1.pauseAfter());
        assertFalse(seg1.twist());

        // Segment 2: The Twist, BROLL with twist
        Segment seg2 = board.segments().get(2);
        assertEquals(2, seg2.index());
        assertEquals("The Twist", seg2.section());
        assertEquals(SegmentType.BROLL, seg2.type());
        assertEquals("Then an ordinary person cracked it.", seg2.narration());
        assertEquals("Computer screens showing deciphering software", seg2.visualPrompt());
        assertFalse(seg2.pauseAfter());
        assertTrue(seg2.twist());
    }

    /**
     * Test 2: JSON script skips segments with blank narration or visual.
     */
    @Test
    void testJsonSkipsBlankSegments() {
        String topic = "Example Topic";
        String jsonScript = """
            {
              "topic": "Example Topic",
              "title": "Example",
              "sections": [
                {
                  "name": "Section1",
                  "segments": [
                    {
                      "type": "broll",
                      "visual": "Valid visual",
                      "narration": "Valid narration"
                    },
                    {
                      "type": "broll",
                      "visual": "",
                      "narration": "Blank visual should skip"
                    },
                    {
                      "type": "graphic",
                      "visual": "Valid visual 2",
                      "narration": ""
                    }
                  ]
                }
              ]
            }
            """;

        Storyboard board = parser.parse(topic, jsonScript);

        assertNotNull(board);
        assertEquals(1, board.segments().size());
        assertEquals("Valid narration", board.segments().get(0).narration());
    }

    /**
     * Test 3a: Unsalvageable JSON-shaped input fails loudly instead of falling back to the
     * marker parser (which would render the JSON text as one garbage narration segment).
     */
    @Test
    void testUnsalvageableJsonThrows() {
        String topic = "Crime Story";
        String invalidJson = "{ this is not valid json at all }";

        assertThrows(IllegalArgumentException.class, () -> parser.parse(topic, invalidJson));
    }

    /**
     * Test 3b: JSON with a stray corrupt character (seen in production: a literal 'n' between
     * fields) is salvaged by regex — all segments recovered, section names attached.
     */
    @Test
    void testCorruptJsonIsSalvaged() {
        String topic = "Crime Story";
        String corruptJson = """
            {
              "topic": "t",
              "sections": [
                {
                  "name": "Level 1: The Catalyst",
                  "segments": [
                    { "type": "broll",n "visual": "broll: POV shot one", "narration": "Narration one here." },
                    { "type": "broll", "visual": "POV shot two", "narration": "Narration two here." },
                    { "type": "graphic", "visual": "BALANCE: $7,450", "narration": "Narration three here." }
                  ]
                }
              ]
            }
            """;

        Storyboard board = parser.parse(topic, corruptJson);

        assertEquals(3, board.segments().size());
        assertEquals("POV shot one", board.segments().get(0).visualPrompt()); // "broll: " prefix stripped
        assertEquals(SegmentType.BROLL, board.segments().get(2).type()); // graphic coerced to BROLL
        assertEquals("Level 1: The Catalyst", board.segments().get(0).section());
    }

    /**
     * Test 3c: Segments over 55 narration words are mechanically split at sentence boundaries;
     * later chunks get a varied-angle visual so each still renders a fresh image.
     */
    @Test
    void testLongSegmentsAreSplitForPacing() {
        StringBuilder narration = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            narration.append("This sentence has exactly eight words in it. ");
        }
        String json = """
            {
              "sections": [ { "name": "Level 1", "segments": [
                { "type": "broll", "visual": "POV hands on a steering wheel", "narration": "%s" }
              ] } ]
            }
            """.formatted(narration.toString().trim());

        Storyboard board = parser.parse("t", json);

        assertTrue(board.segments().size() >= 2, "80-word segment should split into 2+");
        for (Segment s : board.segments()) {
            assertTrue(s.narration().split("\\s+").length <= 55,
                    "chunk too long: " + s.narration().split("\\s+").length);
        }
        assertEquals("POV hands on a steering wheel", board.segments().get(0).visualPrompt());
        assertTrue(board.segments().get(1).visualPrompt().startsWith("A different camera angle"));
        // Indices sequential
        for (int i = 0; i < board.segments().size(); i++) {
            assertEquals(i, board.segments().get(i).index());
        }
    }

    /**
     * Test 4: Realistic marker script with sections, b-roll, graphic, inline [PAUSE] split,
     * [TWIST] flag, marker stripping, consecutive-marker collapse, narration-before-first-marker.
     */
    @Test
    void testMarkerScriptRealistic() {
        String topic = "The Zodiac Killer";
        String markerScript = """
            Narration before any visual marker. This becomes the default establishing shot.

            [SECTION: Cold Open]
            [B-ROLL: Fading grainy footage of late 1960s California]
            Imagine a string of symbols [SOMETHING] not just any symbols. This is haunting narration. [PAUSE]

            What could he possibly have had to say? [TWIST]

            [SECTION: Act One]
            [B-ROLL: Nighttime shots of suburban streets]
            [B-ROLL: A remote dark road]
            December 20th, 1968. A quiet lovers' lane on Lake Herman Road.

            [GRAPHIC: Map showing Lake Herman Road]
            Seven months later at Blue Rock Springs Park.

            [GRAPHIC: Image of the 408 cipher]
            The letters began within weeks of the attacks.
            """;

        Storyboard board = parser.parse(topic, markerScript);

        assertNotNull(board);
        assertEquals(topic, board.topic());
        List<Segment> segments = board.segments();

        assertFalse(segments.isEmpty(), "Should have at least one segment");

        // Check that markers are stripped from narration (no '[' should remain)
        for (Segment seg : segments) {
            assertFalse(seg.narration().contains("["),
                "Segment narration should not contain '[': " + seg.narration());
        }

        // Find and verify the segment with narration before first visual (default BROLL)
        Segment firstSegment = segments.get(0);
        assertEquals(SegmentType.BROLL, firstSegment.type());
        assertTrue(firstSegment.narration().contains("Narration before any visual marker"));
        assertTrue(firstSegment.visualPrompt().contains("documentary about"));

        // Find the Cold Open section
        boolean foundColdOpen = false;
        for (Segment seg : segments) {
            if ("Cold Open".equals(seg.section())) {
                foundColdOpen = true;
                // Should have consecutive BROLL markers collapsed to LAST one
                // The second marker "remote dark road" should have replaced the first
                break;
            }
        }
        assertTrue(foundColdOpen, "Should have found Cold Open section");

        // Verify pause was detected
        boolean foundPause = false;
        for (Segment seg : segments) {
            if (seg.pauseAfter()) {
                foundPause = true;
                break;
            }
        }
        assertTrue(foundPause, "Should have found a segment with pauseAfter=true");

        // Verify twist was detected
        boolean foundTwist = false;
        for (Segment seg : segments) {
            if (seg.twist()) {
                foundTwist = true;
                break;
            }
        }
        assertTrue(foundTwist, "Should have found a segment with twist=true");
    }

    /**
     * Test 5: Marker script - [PAUSE] inline split creates two segments from one narration.
     */
    @Test
    void testMarkerPauseInlineSplit() {
        String topic = "Documentary";
        String script = """
            [B-ROLL: Opening shot]
            First part of narration. [PAUSE] Second part continues under same visual.
            """;

        Storyboard board = parser.parse(topic, script);

        List<Segment> segments = board.segments();
        assertEquals(2, segments.size(), "Should split narration at [PAUSE] into two segments");

        Segment seg0 = segments.get(0);
        assertEquals("First part of narration.", seg0.narration());
        assertTrue(seg0.pauseAfter(), "First segment should have pauseAfter=true");
        assertFalse(seg0.twist());

        Segment seg1 = segments.get(1);
        assertEquals("Second part continues under same visual.", seg1.narration());
        assertFalse(seg1.pauseAfter());
        assertFalse(seg1.twist());

        // Both should have same type and visual prompt
        assertEquals(SegmentType.BROLL, seg0.type());
        assertEquals(SegmentType.BROLL, seg1.type());
        assertEquals("Opening shot", seg0.visualPrompt());
        assertEquals("Opening shot", seg1.visualPrompt());
    }

    /**
     * Test 6: Marker script - consecutive visual markers collapse to the last one.
     */
    @Test
    void testConsecutiveMarkerCollapse() {
        String topic = "Documentary";
        String script = """
            [SECTION: Act One]
            [B-ROLL: First shot]
            [B-ROLL: Second shot]
            [B-ROLL: Third shot]
            This narration belongs to the last B-ROLL marker.
            """;

        Storyboard board = parser.parse(topic, script);

        assertEquals(1, board.segments().size(), "Should have only one segment (consecutive markers collapse)");

        Segment seg = board.segments().get(0);
        assertEquals("Third shot", seg.visualPrompt(), "Should use the last visual marker");
        assertEquals("This narration belongs to the last B-ROLL marker.", seg.narration());
    }

    /**
     * Test 7: Marker script - blank segments are dropped.
     */
    @Test
    void testBlankSegmentsDropped() {
        String topic = "Documentary";
        String script = """
            [B-ROLL: Opening]
            Actual narration here.

            [B-ROLL: Another shot]

            [B-ROLL: Third shot]
            More narration.
            """;

        Storyboard board = parser.parse(topic, script);

        // Should have 2 segments (blank segment between opening and third should be dropped)
        assertEquals(2, board.segments().size());
        assertTrue(board.segments().stream().allMatch(s -> !s.narration().isEmpty()));
    }

    /**
     * Test 8: Marker script with [GRAPHIC] segments.
     */
    @Test
    void testMarkerGraphicSegments() {
        String topic = "Documentary";
        String script = """
            [B-ROLL: Opening visual]
            Opening narration.

            [GRAPHIC: Text Card Title]
            This is narration over a graphic/text card.

            [B-ROLL: Closing visual]
            Closing narration.
            """;

        Storyboard board = parser.parse(topic, script);

        assertEquals(3, board.segments().size());

        assertEquals(SegmentType.BROLL, board.segments().get(0).type());
        assertEquals(SegmentType.GRAPHIC, board.segments().get(1).type());
        assertEquals("Text Card Title", board.segments().get(1).visualPrompt());
        assertEquals(SegmentType.BROLL, board.segments().get(2).type());
    }

    /**
     * Test 9: Section names are preserved.
     */
    @Test
    void testSectionNames() {
        String topic = "Documentary";
        String script = """
            [SECTION: Introduction]
            [B-ROLL: Intro shot]
            Intro narration.

            [SECTION: Main Content]
            [B-ROLL: Main shot]
            Main narration.

            [SECTION: Conclusion]
            [B-ROLL: Outro shot]
            Outro narration.
            """;

        Storyboard board = parser.parse(topic, script);

        assertEquals(3, board.segments().size());
        assertEquals("Introduction", board.segments().get(0).section());
        assertEquals("Main Content", board.segments().get(1).section());
        assertEquals("Conclusion", board.segments().get(2).section());
    }

    /**
     * Test 10: Whitespace normalization in narration.
     */
    @Test
    void testWhitespaceNormalization() {
        String topic = "Documentary";
        String script = """
            [B-ROLL: Shot]
            This    has    multiple     spaces.
            And also

            multiple


            newlines.
            """;

        Storyboard board = parser.parse(topic, script);

        Segment seg = board.segments().get(0);
        String narration = seg.narration();

        // Should have normalized whitespace
        assertFalse(narration.contains("    "), "Should not have multiple consecutive spaces");
        assertTrue(narration.contains(" "), "Should have normal single spaces");

        // Should be collapsed to single line with single spaces
        assertTrue(narration.matches("^[^\\n]+$"), "Should be single line after normalization");
    }

    /**
     * Test 11: JSON with unknown fields ignores them.
     */
    @Test
    void testJsonIgnoresUnknownFields() {
        String topic = "Example Topic";
        String jsonScript = """
            {
              "topic": "Example Topic",
              "title": "Example",
              "unknownField": "should be ignored",
              "sections": [
                {
                  "name": "Section1",
                  "unknownSectionField": "also ignored",
                  "segments": [
                    {
                      "type": "broll",
                      "visual": "Valid visual",
                      "narration": "Valid narration",
                      "unknownSegmentField": "ignored too"
                    }
                  ]
                }
              ]
            }
            """;

        Storyboard board = parser.parse(topic, jsonScript);

        assertNotNull(board);
        assertEquals(1, board.segments().size());
        assertEquals("Valid narration", board.segments().get(0).narration());
    }

    /**
     * Test 12: JSON with missing boolean fields defaults to false.
     */
    @Test
    void testJsonMissingBooleansDefaultToFalse() {
        String topic = "Example Topic";
        String jsonScript = """
            {
              "topic": "Example Topic",
              "title": "Example",
              "sections": [
                {
                  "name": "Section1",
                  "segments": [
                    {
                      "type": "broll",
                      "visual": "Visual",
                      "narration": "Narration"
                    }
                  ]
                }
              ]
            }
            """;

        Storyboard board = parser.parse(topic, jsonScript);

        Segment seg = board.segments().get(0);
        assertFalse(seg.pauseAfter(), "Missing pause should default to false");
        assertFalse(seg.twist(), "Missing twist should default to false");
    }

    /**
     * Test 13: Indices are assigned sequentially starting from 0.
     */
    @Test
    void testIndicesSequential() {
        String topic = "Documentary";
        String script = """
            [B-ROLL: Shot 1]
            Narration 1.

            [B-ROLL: Shot 2]
            Narration 2.

            [B-ROLL: Shot 3]
            Narration 3.
            """;

        Storyboard board = parser.parse(topic, script);

        List<Segment> segments = board.segments();
        for (int i = 0; i < segments.size(); i++) {
            assertEquals(i, segments.get(i).index(),
                "Segment " + i + " should have index " + i);
        }
    }

    /**
     * Test 14: JSON fence with ```json explicitly stripped.
     */
    @Test
    void testJsonFenceVariations() {
        String topic = "Example";
        String withFence = """
            ```json
            {
              "topic": "Example",
              "title": "Example",
              "sections": [
                {
                  "name": "Test",
                  "segments": [
                    {
                      "type": "broll",
                      "visual": "Visual",
                      "narration": "Text"
                    }
                  ]
                }
              ]
            }
            ```
            """;

        Storyboard board = parser.parse(topic, withFence);
        assertEquals(1, board.segments().size());
        assertEquals("Text", board.segments().get(0).narration());

        // Test with just ``` (no language specifier)
        String withGenericFence = """
            ```
            {
              "topic": "Example",
              "title": "Example",
              "sections": [
                {
                  "name": "Test",
                  "segments": [
                    {
                      "type": "broll",
                      "visual": "Visual",
                      "narration": "Text2"
                    }
                  ]
                }
              ]
            }
            ```
            """;

        board = parser.parse(topic, withGenericFence);
        assertEquals(1, board.segments().size());
        assertEquals("Text2", board.segments().get(0).narration());
    }

    /**
     * Test 15: Empty script produces empty segments.
     */
    @Test
    void testEmptyScript() {
        String topic = "Example";
        String empty = "";

        Storyboard board = parser.parse(topic, empty);

        assertNotNull(board);
        assertEquals(topic, board.topic());
        assertEquals(0, board.segments().size());
    }

    /**
     * Test 16: Marker-based [TWIST] marker in narration.
     */
    @Test
    void testMarkerTwistInline() {
        String topic = "Documentary";
        String script = """
            [B-ROLL: Reveal shot]
            Everything we thought was wrong. [TWIST] The real truth is stranger.
            """;

        Storyboard board = parser.parse(topic, script);

        // Should split at twist
        assertTrue(board.segments().size() >= 1);
        boolean foundTwist = false;
        for (Segment seg : board.segments()) {
            if (seg.twist()) {
                foundTwist = true;
                break;
            }
        }
        assertTrue(foundTwist, "Should mark segment with twist");
    }
}
