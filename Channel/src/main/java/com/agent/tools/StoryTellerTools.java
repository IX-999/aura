package com.agent.tools;

import com.google.adk.tools.Annotations.Schema;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.StorageOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tools exposed to the storyteller agent.
 *
 * IMPORTANT: compile with -parameters (maven.compiler.parameters=true) or ADK
 * cannot read parameter names when it builds the tool schema.
 *
 * All methods must return Map<String, String> (or another ADK-serializable type)
 * and should never throw — return an error status instead so the agent can recover.
 */
public class StoryTellerTools {

    /**
     * Step 2: hard content screen. The agent MUST call this before committing
     * to a topic. Deterministic — not subject to prompt persuasion.
     *
     * Trending discovery now lives in {@link TrendingTopicsTool} (YouTube +
     * TikTok). This class keeps the screening, dedupe, research, and save tools.
     */
    /**
     * The ONLY blocked categories: illegal, exploitative, or instructional-harm content.
     * Everything else — controversial, political, dark, sensitive — is a plain ALLOW.
     */
    private static final java.util.List<String> HARD_BLOCKED_TERMS = java.util.List.of(
            "child pornography", "child sexual abuse", "revenge porn", "sex tape leak",
            "non-consensual pornography",
            "how to buy drugs", "how to make meth", "how to make a bomb", "how to build a bomb",
            "how to poison", "how to hack", "dark web market", "hire a hitman",
            "dox this person", "expose their address", "leak private information",
            "harass this person", "attack this person",
            "graphic murder footage", "uncensored execution", "celebrate the killing",
            "glorify the murderer");

    public static Map<String, String> screenTopicSafety(
            @Schema(description = "The candidate topic to screen for content policy violations")
            String topic) {

        String normalized = topic == null ? "" : topic.toLowerCase(Locale.ROOT)
                .replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();
        String matched = HARD_BLOCKED_TERMS.stream()
                .filter(normalized::contains).findFirst().orElse(null);

        Map<String, String> out = new HashMap<>();
        out.put("topic", topic == null ? "" : topic);
        if (normalized.isBlank() || matched != null) {
            out.put("verdict", "BLOCK");
            out.put("approved", "false");
            out.put("reason", "Illegal, exploitative, or instructional-harm content is never generated.");
            if (matched != null) {
                out.put("matchedTerm", matched);
            }
        } else {
            out.put("verdict", "ALLOW");
            out.put("approved", "true");
            out.put("reason", "Topic approved — all subjects including controversial ones are allowed.");
        }
        return out;
    }

    /** Local ledger of everything the pipeline has scripted; one normalized topic per line. */
    private static final Path PUBLISHED_TOPICS_FILE = Path.of("published-topics.txt");

    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "a", "an", "the", "and", "or", "of", "in", "on", "to", "for", "with", "your",
            "you", "youre", "you're", "is", "are", "was", "were", "now", "its", "it's",
            "pov", "my", "his", "her", "their", "from", "at", "by", "into", "after");

    /**
     * Step 3: dedupe against what the channel already scripted. Two independent gates:
     * <ol>
     *   <li>PROFESSION: the role in "POV: You're a {role} and ..." (or "the life of a {role}")
     *       must not share a distinctive word with any prior role. Catches
     *       "Bail Bondsman" vs "Bail Enforcement Agent", which word-overlap misses because
     *       the surrounding hooks differ.</li>
     *   <li>THEME: >=60% of significant words shared with a prior topic. Catches
     *       "living in your car" vs "your car is now your home".</li>
     * </ol>
     * The response includes recent topics so the agent can steer AWAY from tired themes.
     */
    public static Map<String, String> checkTopicPublished(
            @Schema(description = "Topic to check against the channel's publish history")
            String topic) {

        Map<String, String> out = new HashMap<>();
        out.put("topic", topic);
        out.put("status", "success");

        java.util.List<String> published = readPublishedTopics();
        java.util.Set<String> candidate = significantWords(topic);
        java.util.Set<String> candidateRole = professionWords(topic);

        String collision = null;
        String collisionKind = null;
        for (String prior : published) {
            // Gate 1: same profession — one shared distinctive role word is enough.
            java.util.Set<String> priorRole = professionWords(prior);
            if (!candidateRole.isEmpty() && priorRole.stream().anyMatch(candidateRole::contains)) {
                collision = prior;
                collisionKind = "profession";
                break;
            }

            // Gate 2: same theme by word overlap.
            java.util.Set<String> priorWords = significantWords(prior);
            if (candidate.isEmpty() || priorWords.isEmpty()) {
                continue;
            }
            long shared = candidate.stream().filter(priorWords::contains).count();
            double overlap = (double) shared / Math.min(candidate.size(), priorWords.size());
            if (overlap >= 0.6) {
                collision = prior;
                collisionKind = "theme";
                break;
            }
        }
        if (collisionKind != null) {
            out.put("collisionKind", collisionKind);
        }

        if (collision != null) {
            out.put("alreadyPublished", "true");
            out.put("collidesWith", collision);
            out.put("reason", "profession".equals(collisionKind)
                    ? "This PROFESSION has already been covered (\"" + collision + "\"). Pick a "
                            + "completely different job/world — not a variant of the same role."
                    : "Too similar to an already-produced story. Pick a DIFFERENT "
                            + "profession/world and a DIFFERENT premise — not a rewording of this one.");
        } else {
            out.put("alreadyPublished", "false");
        }
        if (!published.isEmpty()) {
            int from = Math.max(0, published.size() - 8);
            out.put("recentTopics", String.join(" | ", published.subList(from, published.size())));
            out.put("varietyNote", "Recently covered themes above — choose something clearly "
                    + "different in setting, profession, and premise.");
        }
        return out;
    }

    private static java.util.List<String> readPublishedTopics() {
        try {
            if (Files.exists(PUBLISHED_TOPICS_FILE)) {
                return Files.readAllLines(PUBLISHED_TOPICS_FILE, StandardCharsets.UTF_8).stream()
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
            }
        } catch (Exception ignored) {
            // Dedupe is best-effort; a read failure must not block production.
        }
        return java.util.List.of();
    }

    /**
     * Role words that describe almost any job and so must never, on their own, make two
     * professions "the same" (e.g. every second title has "owner" or "worker" in it).
     */
    private static final java.util.Set<String> GENERIC_ROLE_WORDS = java.util.Set.of(
            "owner", "worker", "employee", "staff", "person", "man", "woman", "guy",
            "job", "life", "work", "professional", "expert", "boss",
            // pure size/age modifiers — never the identity of a job
            "small", "big", "new", "young", "old", "full", "part", "time");

    private static final java.util.regex.Pattern[] ROLE_PATTERNS = {
            // "POV: You're a Bail Bondsman and ..." / "POV: You are an Armored Car Guard, ..."
            java.util.regex.Pattern.compile(
                    "you'?re\\s+an?\\s+(.+?)(?=\\s+and\\b|,|$)", java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile(
                    "you\\s+are\\s+an?\\s+(.+?)(?=\\s+and\\b|,|$)", java.util.regex.Pattern.CASE_INSENSITIVE),
            // "The life of a Bail Bondsman" / "The experience of an Armored Car Guard"
            java.util.regex.Pattern.compile(
                    "(?:life|world|experience|day|story)\\s+of\\s+an?\\s+(.+?)(?=\\s+and\\b|,|$)",
                    java.util.regex.Pattern.CASE_INSENSITIVE),
    };

    /**
     * Distinctive words of the topic's profession/role, or empty when no role is stated.
     * "POV: You're a Bail Bondsman and Your First Client..." -> {bail, bondsman}
     * "The life of a Bail Bondsman"                          -> {bail, bondsman}
     */
    static java.util.Set<String> professionWords(String topic) {
        if (topic == null || topic.isBlank()) {
            return java.util.Set.of();
        }
        for (java.util.regex.Pattern pattern : ROLE_PATTERNS) {
            java.util.regex.Matcher m = pattern.matcher(topic);
            if (m.find()) {
                java.util.Set<String> words = new java.util.HashSet<>(significantWords(m.group(1)));
                words.removeAll(GENERIC_ROLE_WORDS);
                return words;
            }
        }
        return java.util.Set.of();
    }

    private static java.util.Set<String> significantWords(String topic) {
        java.util.Set<String> words = new java.util.HashSet<>();
        if (topic != null) {
            for (String w : topic.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").split("\\s+")) {
                if (w.length() > 2 && !STOPWORDS.contains(w)) {
                    words.add(w);
                }
            }
        }
        return words;
    }

    /**
     * Step 4: research. This is the accuracy backbone — the agent is instructed
     * that every factual claim in the script must trace back to this output.
     *
     * Wire this to Vertex AI Search with Google Search grounding, or to a
     * Custom Search API call. Return real source URLs so the agent can cite them.
     */
    public static Map<String, String> researchTopic(
            @Schema(description = "The topic to research in depth")
            String topic,
            @Schema(description = "Specific angle or question to investigate, or 'general' for an overview")
            String angle) {

        Map<String, String> out = new HashMap<>();
        try {
            // TODO: replace with grounded search. Return findings + source URLs.
            out.put("status", "success");
            out.put("topic", topic);
            out.put("angle", angle);
            out.put("findings", "PLACEHOLDER — wire this to grounded search. "
                    + "Return 5-10 concrete facts with dates, numbers, and names.");
            out.put("sources", "PLACEHOLDER — return real URLs here.");
            out.put("confidence", "low");
        } catch (Exception e) {
            out.put("status", "error");
            out.put("message", "Research failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Step 5: persist the finished script so the downstream audio/video stages
     * can pick it up. Swap for a GCS write.
     */
    /**
     * Narration below this word count is rejected. Measured, not assumed: Chirp3-HD at
     * speaking-rate 1.0 delivered 3,501 words in 19m35s (~179 wpm), so the 20-minute floor
     * needs ~3,600 words.
     */
    private static final int MIN_NARRATION_WORDS = 3600;

    public static Map<String, String> saveScript(
            @Schema(description = "The topic the script covers")
            String topic,
            @Schema(description = "The full script text including all markers")
            String scriptText) {

        Map<String, String> out = new HashMap<>();
        scriptText = repairMojibake(scriptText);

        // Segment LENGTH is not gated here: rejection proved unreliable (the agent gave up
        // rather than splitting). DefaultScriptParser mechanically splits >55-word segments
        // at render time, so pacing is guaranteed regardless of model compliance.
        int narrationWords = countNarrationWords(scriptText);

        if (narrationWords > 0 && narrationWords < MIN_NARRATION_WORDS) {
            out.put("status", "error");
            out.put("topic", topic);
            out.put("narrationWordCount", String.valueOf(narrationWords));
            out.put("message", "Script REJECTED: only " + narrationWords + " narration words — that is a ~"
                    + (narrationWords / 150) + " minute video; the minimum is " + MIN_NARRATION_WORDS
                    + " words (20+ minutes). Do NOT resubmit the same script. EXPAND it: add new scenes, "
                    + "setbacks, side characters, and rules to Levels 3-6 until each has 10-12 segments "
                    + "of 60-80 narration words, then call saveScript again.");
            return out;
        }

        out.put("status", "success");
        out.put("topic", topic);
        out.put("wordCount", String.valueOf(scriptText.split("\\s+").length));
        if (narrationWords > 0) {
            out.put("narrationWordCount", String.valueOf(narrationWords));
        }

        // Record the accepted topic so checkTopicPublished blocks same-theme repeats.
        try {
            Files.writeString(PUBLISHED_TOPICS_FILE, topic.trim() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Ledger append is best-effort.
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String fileName = timestamp + "-" + slugify(topic) + ".md";

        // Always write a local fallback copy first — this must never fail the tool.
        String localPath = null;
        try {
            Path scriptsDir = Path.of("scripts");
            Files.createDirectories(scriptsDir);
            Path localFile = scriptsDir.resolve(fileName);
            Files.writeString(localFile, scriptText, StandardCharsets.UTF_8);
            localPath = localFile.toAbsolutePath().toString();
        } catch (Exception e) {
            // Best effort — fall through; GCS write (if it succeeds) still gets reported.
        }

        String gcsUrl = null;
        try {
            String bucket = System.getenv().getOrDefault("AURA_MEDIA_BUCKET", "aura-channel-media-vivid-now-390717");
            String objectName = "scripts/" + fileName;
            BlobId blobId = BlobId.of(bucket, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/markdown").build();
            String projectId = System.getenv().getOrDefault("GOOGLE_CLOUD_PROJECT", "vivid-now-390717");
            StorageOptions.newBuilder().setProjectId(projectId).build().getService()
                    .create(blobInfo, scriptText.getBytes(StandardCharsets.UTF_8));
            gcsUrl = "gs://" + bucket + "/" + objectName;
        } catch (Exception e) {
            // Swallow — never throw. Fall back to the local copy below.
        }

        if (gcsUrl != null) {
            out.put("savedTo", gcsUrl);
            if (localPath != null) {
                out.put("localCopy", localPath);
            }
        } else if (localPath != null) {
            out.put("savedTo", localPath);
        } else {
            out.put("savedTo", "none — both GCS upload and local write failed");
        }

        return out;
    }

    /**
     * Sums the words inside the JSON "narration" fields only (visual prompts and JSON
     * scaffolding don't count toward runtime). Returns 0 for non-JSON (marker) scripts,
     * which skips the length gate rather than mis-measuring them.
     */
    private static int countNarrationWords(String scriptText) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"narration\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(scriptText);
        int words = 0;
        while (m.find()) {
            String narration = m.group(1).trim();
            if (!narration.isEmpty()) {
                words += narration.split("\\s+").length;
            }
        }
        return words;
    }

    /**
     * Model output sometimes arrives UTF-8-bytes-decoded-as-Latin-1 ("â€™" instead of "'"),
     * which the TTS then reads aloud. Re-decoding restores the original characters.
     */
    public static String repairMojibake(String text) {
        // Repeat because text double-mangled in transit needs two passes
        // ("Ã¢Â€Â¦" -> "â€¦" -> "…").
        for (int i = 0; i < 3 && text != null
                && (text.contains("â€") || text.contains("Ã©") || text.contains("Ã¢")); i++) {
            String repaired = new String(text.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            if (repaired.contains("�") || repaired.equals(text)) {
                break;
            }
            text = repaired;
        }
        return text;
    }

    private static String slugify(String text) {
        String slug = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "untitled" : slug;
    }
}