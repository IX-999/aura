package com.agent.video.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;

/**
 * Searches Wikimedia Commons for a free-license photo matching a prompt.
 * Plain java.net.http.HttpClient, no auth required, but Wikimedia mandates a User-Agent.
 * Not a Spring bean itself — wired directly by {@link CompositeVisualService}.
 */
public class WikimediaSearch {

    private static final Logger log = LoggerFactory.getLogger(WikimediaSearch.class);
    private static final String USER_AGENT = "AuraChannelBot/1.0 (https://example.com/aura-channel; contact@example.com)";
    private static final int MIN_WIDTH = 1280;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Searches Commons for the prompt and downloads the first hit at least 1280px wide.
     * Returns null (does not throw) if no usable result was found.
     */
    public Path search(String prompt, int segmentIndex, Path imageDir) throws Exception {
        String query = "filetype:bitmap " + prompt;
        String url = "https://commons.wikimedia.org/w/api.php"
                + "?action=query&generator=search&gsrsearch=" + urlEncode(query)
                + "&gsrnamespace=6&gsrlimit=10&prop=imageinfo&iiprop=url%7Csize&format=json";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            log.warn("Wikimedia search failed for '{}': HTTP {}", prompt, response.statusCode());
            return null;
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode pages = root.path("query").path("pages");
        if (!pages.isObject() && !pages.isArray()) {
            return null;
        }

        String bestUrl = null;
        Iterator<JsonNode> it = pages.isObject() ? pages.elements() : pages.iterator();
        while (it.hasNext()) {
            JsonNode page = it.next();
            JsonNode imageInfoArr = page.path("imageinfo");
            if (!imageInfoArr.isArray() || imageInfoArr.isEmpty()) {
                continue;
            }
            JsonNode info = imageInfoArr.get(0);
            int width = info.path("width").asInt(0);
            String fileUrl = info.path("url").asText(null);
            if (fileUrl != null && width >= MIN_WIDTH) {
                bestUrl = fileUrl;
                break;
            }
        }

        if (bestUrl == null) {
            log.info("Wikimedia: no result >= {}px for '{}'", MIN_WIDTH, prompt);
            return null;
        }

        return download(bestUrl, segmentIndex, imageDir);
    }

    private Path download(String fileUrl, int segmentIndex, Path imageDir) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fileUrl))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Wikimedia download failed: HTTP " + response.statusCode() + " for " + fileUrl);
        }

        String ext = fileUrl.substring(fileUrl.lastIndexOf('.') + 1).toLowerCase();
        if (ext.length() > 4 || ext.isBlank()) {
            ext = "jpg";
        }
        Path out = imageDir.resolve(String.format("seg-%03d-wiki.%s", segmentIndex, ext));
        Files.write(out, response.body());
        log.info("Wikimedia: segment {} -> {}", segmentIndex, out);
        return out;
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
