package com.agent.policy;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Deterministic topic-classification guardrail.
 *
 * The policy favors interesting, unusual, mysterious, debated, and
 * controversial topics while blocking content that is explicitly harmful,
 * exploitative, instructional, or unsafe.
 */
public final class ContentPolicy {

    private ContentPolicy() {
    }

    /**
     * Topics that should not be generated, regardless of framing.
     *
     * These terms focus on exploitative sexual content, instructions for
     * wrongdoing, targeted harassment, and graphic violence.
     */
    private static final List<String> BLOCKED_TERMS = List.of(
            // Exploitative or explicit sexual content
            "child pornography",
            "child sexual abuse",
            "revenge porn",
            "sex tape leak",
            "non-consensual pornography",
            // Instructions for illegal or dangerous activity
            "how to buy drugs",
            "how to make meth",
            "how to make a bomb",
            "how to build a bomb",
            "how to poison",
            "how to hack",
            "dark web market",
            "hire a hitman",
            // Targeted harassment or abuse
            "dox this person",
            "expose their address",
            "leak private information",
            "harass this person",
            "attack this person",
            // Graphic or celebratory violence
            "graphic murder footage",
            "uncensored execution",
            "celebrate the killing",
            "glorify the murderer"
    );

    /**
     * Controversial topics are allowed because they may produce compelling
     * stories, but they require balanced, factual, and neutral treatment.
     */
    private static final List<String> CONTROVERSIAL_TERMS = List.of(
            // Politics and public debate
            "politics",
            "political",
            "president",
            "government",
            "election",
            "election fraud",
            "abortion",
            "gun control",
            "immigration",
            "vaccine mandate",
            "trans rights",
            "gender ideology",
            "critical race theory",
            // Wars and international conflicts
            "israel",
            "palestine",
            "gaza",
            "ukraine war",
            "war crimes",
            "terrorism",
            // Religion and ideology
            "religion",
            "religious controversy",
            "cult",
            "conspiracy",
            "extremism",
            // Public disputes
            "cancel culture",
            "scandal",
            "lawsuit",
            "cover-up",
            "corruption",
            "whistleblower",
            "corporate misconduct",
            "celebrity feud"
    );

    /**
     * High-interest topics are especially suitable for story generation.
     */
    private static final List<String> INTERESTING_TERMS = List.of(
            // True crime and investigations
            "true crime",
            "unsolved",
            "cold case",
            "missing person",
            "mystery",
            "mysterious",
            "investigation",
            "detective",
            "heist",
            "fraud",
            "forensic",
            "serial killer",
            "criminal case",
            // Science and technology
            "artificial intelligence",
            "ai",
            "space",
            "nasa",
            "quantum",
            "breakthrough",
            "discovery",
            "experiment",
            "invention",
            "cybersecurity",
            "data breach",
            // History and hidden stories
            "ancient",
            "archaeology",
            "lost civilization",
            "historical mystery",
            "secret project",
            "declassified",
            "hidden history",
            "unknown story",
            // Disasters and extraordinary events
            "disaster",
            "accident",
            "crash",
            "survival",
            "rescue",
            "expedition",
            "shipwreck",
            "aviation",
            "engineering failure",
            // Unusual or surprising stories
            "bizarre",
            "unexpected",
            "unexplained",
            "controversial",
            "remarkable",
            "strange",
            "viral",
            "record-breaking"
    );

    /**
     * Sensitive subjects can be covered, but must avoid graphic descriptions,
     * sensationalism, victim exploitation, and unsupported allegations.
     */
    private static final List<String> SENSITIVE_TERMS = List.of(
            "murder",
            "homicide",
            "suicide",
            "death",
            "died",
            "victim",
            "sexual assault",
            "rape",
            "domestic violence",
            "child abuse",
            "mass shooting",
            "disease",
            "pandemic",
            "mental illness",
            "addiction"
    );

    public enum Verdict {
        /**
         * Safe, but not identified as especially interesting.
         */
        ALLOW,
        /**
         * Suitable and likely to produce an engaging story.
         */
        ALLOW_INTERESTING,
        /**
         * Controversial or sensitive topic requiring careful treatment.
         */
        ALLOW_WITH_CARE,
        /**
         * Explicitly unsafe or exploitative content.
         */
        BLOCK
    }

    public record Result(
            Verdict verdict,
            String reason,
            Optional<String> matchedTerm) {

        public boolean isBlocked() {
            return verdict == Verdict.BLOCK;
        }

        public boolean requiresCare() {
            return verdict == Verdict.ALLOW_WITH_CARE;
        }

        public boolean isInteresting() {
            return verdict == Verdict.ALLOW_INTERESTING
                    || verdict == Verdict.ALLOW_WITH_CARE;
        }
    }

    public static Result screen(String topic) {
        if (topic == null || topic.isBlank()) {
            return new Result(
                    Verdict.BLOCK,
                    "The topic is empty.",
                    Optional.empty());
        }

        String normalized = normalize(topic);

        Optional<String> blockedTerm = findMatch(
                normalized,
                BLOCKED_TERMS);

        if (blockedTerm.isPresent()) {
            return new Result(
                    Verdict.BLOCK,
                    "The topic requests unsafe, exploitative, or explicitly "
                    + "harmful content and must not be generated.",
                    blockedTerm);
        }

        Optional<String> sensitiveTerm = findMatch(
                normalized,
                SENSITIVE_TERMS);

        if (sensitiveTerm.isPresent()) {
            return new Result(
                    Verdict.ALLOW_WITH_CARE,
                    "This is a potentially compelling but sensitive topic. "
                    + "Use verified sources, respect victims, avoid "
                    + "graphic details, do not glorify offenders, and "
                    + "clearly distinguish facts from allegations.",
                    sensitiveTerm);
        }

        Optional<String> controversialTerm = findMatch(
                normalized,
                CONTROVERSIAL_TERMS);

        if (controversialTerm.isPresent()) {
            return new Result(
                    Verdict.ALLOW_WITH_CARE,
                    "This controversial topic may produce an engaging story. "
                    + "Present multiple credible perspectives, remain "
                    + "fact-based, avoid inflammatory framing, and "
                    + "clearly label disputed or unverified claims.",
                    controversialTerm);
        }

        Optional<String> interestingTerm = findMatch(
                normalized,
                INTERESTING_TERMS);

        if (interestingTerm.isPresent()) {
            return new Result(
                    Verdict.ALLOW_INTERESTING,
                    "This topic contains a strong curiosity, mystery, conflict, "
                    + "discovery, or human-interest element and is a "
                    + "good candidate for an engaging story.",
                    interestingTerm);
        }

        return new Result(
                Verdict.ALLOW,
                "The topic is permitted, but no strong controversy or "
                + "high-interest signal was detected.",
                Optional.empty());
    }

    private static Optional<String> findMatch(
            String normalizedTopic,
            List<String> terms) {

        return terms.stream()
                .filter(normalizedTopic::contains)
                .findFirst();
    }

    private static String normalize(String topic) {
        return topic
                .toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
