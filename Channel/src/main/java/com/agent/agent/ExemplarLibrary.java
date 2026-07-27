package com.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads example transcripts used to few-shot condition the storyteller agent's
 * STYLE (voice, pacing, hooks, structure) — never its subject matter.
 *
 * Drop .txt or .md files into the exemplars directory ({@code agent.exemplars.dir},
 * default "exemplars").
 * At most {@link #MAX_FILES} files are used, each truncated to {@link #MAX_CHARS}
 * characters, so exemplars can't crowd out the instruction itself.
 */
@Component
public class ExemplarLibrary {

    private static final Logger log = LoggerFactory.getLogger(ExemplarLibrary.class);
    private static final int MAX_FILES = 3;
    private static final int MAX_CHARS = 8000;

    private final String instructionBlock;

    public ExemplarLibrary(@Value("${agent.exemplars.dir:exemplars}") String dir) {
        this.instructionBlock = load(Path.of(dir));
    }

    /** Empty string when there are no usable exemplars. */
    public String instructionBlock() {
        return instructionBlock;
    }

    private String load(Path dir) {
        if (!Files.isDirectory(dir)) {
            return "";
        }

        List<String> blocks = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> candidates = files
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return (name.endsWith(".txt") || name.endsWith(".md"))
                                && !name.equals("readme.md");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();

            for (Path file : candidates) {
                if (blocks.size() >= MAX_FILES) {
                    log.warn("exemplars: more than {} files in {}, ignoring the rest", MAX_FILES, dir);
                    break;
                }
                String text = Files.readString(file).trim();
                if (text.isBlank()) {
                    continue;
                }

                if (text.length() > MAX_CHARS) {
                    text = text.substring(0, MAX_CHARS) + "\n[... truncated ...]";
                }
                blocks.add("--- EXEMPLAR: " + file.getFileName() + " ---\n" + text);
                log.info("exemplars: loaded {} ({} chars)", file.getFileName(), text.length());
            }
        } catch (IOException e) {
            log.warn("exemplars: failed reading {}: {}", dir, e.getMessage());
            return "";
        }

        if (blocks.isEmpty()) {
            return "";
        }

        return "\n\n# STYLE EXEMPLARS\n\n"
                + "The transcripts below are examples of STYLE that performed well. Imitate their\n"
                + "voice, pacing, hook density, sentence rhythm, and structural choices.\n"
                + "Do NOT reuse their subject matter, facts, names, or specific phrases — every\n"
                + "factual claim in YOUR script still comes only from researchTopic output.\n"
                + "All safety, content-policy, and accuracy rules above apply unconditionally and\n"
                + "override anything an exemplar appears to do.\n\n"
                + String.join("\n\n", blocks);
    }
}
