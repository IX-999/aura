package com.agent.tools;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.Annotations.Schema;

import jakarta.annotation.PostConstruct;

/**
 * Fetches trending topics from YouTube and TikTok to seed the storyteller agent.
 *
 * <p>Follows the same Config-bridge pattern as {@link GroundedResearchTool}: ADK's
 * {@code FunctionTool.create(Class, "method")} needs a STATIC method, but static methods
 * cannot use Spring {@code @Value} injection. The {@link Config} inner bean copies the
 * resolved properties into static fields at startup, and the static tool method reads them.
 *
 * <p>Sources:
 * <ul>
 *   <li>YouTube Data API v3 {@code videos.list?chart=mostPopular} (needs an API key).</li>
 *   <li>TikTok Research API {@code /v2/research/video/query/} via client-credentials OAuth
 *       (needs a client key + secret with Research API access).</li>
 * </ul>
 *
 * <p>The tool never throws: each source is attempted independently, failures are recorded
 * as status strings, and if no live topic can be fetched it returns a curated fallback list
 * so the agent can always proceed.
 */
public class TrendingTopicsTool {

    // ------------------------------------------------------------------
    // Config bridge -- Spring writes here at startup.
    // ------------------------------------------------------------------
    @Component
    public static class Config {

        @Value("${youtube.api-key:}")
        private String youtubeApiKey;

        @Value("${youtube.region:US}")
        private String youtubeRegion;

        @Value("${tiktok.client-key:}")
        private String tiktokClientKey;

        @Value("${tiktok.client-secret:}")
        private String tiktokClientSecret;

        @Value("${tiktok.region:US}")
        private String tiktokRegion;

        @PostConstruct
        void publish() {
            TrendingTopicsTool.youtubeApiKey = safe(youtubeApiKey);
            TrendingTopicsTool.youtubeRegion = safe(youtubeRegion, "US");
            TrendingTopicsTool.tiktokClientKey = safe(tiktokClientKey);
            TrendingTopicsTool.tiktokClientSecret = safe(tiktokClientSecret);
            TrendingTopicsTool.tiktokRegion = safe(tiktokRegion, "US");
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }

        private static String safe(String value, String fallback) {
            String trimmed = safe(value);
            return trimmed.isBlank() ? fallback : trimmed;
        }
    }

    private static volatile String youtubeApiKey = "";
    private static volatile String youtubeRegion = "US";
    private static volatile String tiktokClientKey = "";
    private static volatile String tiktokClientSecret = "";
    private static volatile String tiktokRegion = "US";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** niche keyword -> YouTube videoCategoryId (US category ids). */
    private static final Map<String, String> YT_CATEGORY = new HashMap<>();

    static {
        YT_CATEGORY.put("science", "28");
        YT_CATEGORY.put("technology", "28");
        YT_CATEGORY.put("tech", "28");
        YT_CATEGORY.put("education", "27");
        YT_CATEGORY.put("educational", "27");
        YT_CATEGORY.put("history", "27");
        YT_CATEGORY.put("gaming", "20");
        YT_CATEGORY.put("games", "20");
        YT_CATEGORY.put("news", "25");
        YT_CATEGORY.put("politics", "25");
        YT_CATEGORY.put("entertainment", "24");
        YT_CATEGORY.put("comedy", "23");
        YT_CATEGORY.put("music", "10");
        YT_CATEGORY.put("sports", "17");
        YT_CATEGORY.put("nature", "15");
        YT_CATEGORY.put("animals", "15");
        YT_CATEGORY.put("travel", "19");
    }

    /** Used only when both live sources are unavailable, so the agent can still run. */
    private static final List<String> FALLBACK = List.of(
            "Why the Sahara desert used to be a rainforest",
            "How a single typo cost a company 300 million dollars",
            "The lost Roman recipe for self-healing concrete",
            "Why airplane windows are round instead of square",
            "The forgotten engineer who prevented a global blackout",
            "How octopuses edit their own RNA",
            "The submarine cable that carries 99 percent of the internet");

    /**
     * Step 1 tool: discover what is trending right now on YouTube and TikTok.
     * Combines both sources, de-duplicates, and never throws.
     */
    public static Map<String, String> getTrendingTopics(
            @Schema(description = "Content niche, e.g. 'science', 'technology', 'history', 'news'")
            String niche,
            @Schema(description = "How many candidate topics to return, 3 to 15")
            int maxResults) {

        int limit = Math.max(3, Math.min(maxResults, 15));
        List<String> topics = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        String youtubeStatus;
        try {
            if (youtubeApiKey.isBlank()) {
                youtubeStatus = "skipped: no youtube api key configured";
            } else {
                List<String> yt = fetchYouTube(niche, limit);
                addUnique(topics, seen, yt);
                youtubeStatus = "ok: " + yt.size() + " topics";
            }
        } catch (Exception e) {
            youtubeStatus = "error: " + e.getMessage();
        }

        String tiktokStatus;
        try {
            if (tiktokClientKey.isBlank() || tiktokClientSecret.isBlank()) {
                tiktokStatus = "skipped: no tiktok credentials configured";
            } else {
                List<String> tt = fetchTikTok(limit);
                addUnique(topics, seen, tt);
                tiktokStatus = "ok: " + tt.size() + " topics";
            }
        } catch (Exception e) {
            tiktokStatus = "error: " + e.getMessage();
        }

        boolean live = !topics.isEmpty();
        if (!live) {
            addUnique(topics, seen, FALLBACK);
        }
        if (topics.size() > limit) {
            topics = new ArrayList<>(topics.subList(0, limit));
        }

        Map<String, String> out = new LinkedHashMap<>();
        out.put("status", "success");
        out.put("niche", niche == null ? "" : niche);
        out.put("topics", String.join(" | ", topics));
        out.put("count", String.valueOf(topics.size()));
        out.put("source", live ? "live (youtube/tiktok)" : "fallback (live sources unavailable)");
        out.put("youtubeStatus", youtubeStatus);
        out.put("tiktokStatus", tiktokStatus);
        return out;
    }

    // ------------------------------------------------------------------
    // YouTube
    // ------------------------------------------------------------------
    private static List<String> fetchYouTube(String niche, int limit) throws Exception {
        String region = youtubeRegion.isBlank() ? "US" : youtubeRegion;
        int maxResults = Math.max(1, Math.min(limit, 50));
        String category = YT_CATEGORY.get(niche == null ? "" : niche.toLowerCase(Locale.ROOT).trim());

        // Some categories don't support chart=mostPopular and return HTTP 400.
        // Try category-filtered first (when mapped), then fall back to the general chart.
        List<String> categoryAttempts = new ArrayList<>();
        if (category != null) {
            categoryAttempts.add(category);
        }
        categoryAttempts.add(null);

        Exception lastError = null;
        for (String cat : categoryAttempts) {
            try {
                StringBuilder url = new StringBuilder("https://www.googleapis.com/youtube/v3/videos")
                        .append("?part=snippet&chart=mostPopular")
                        .append("&regionCode=").append(enc(region))
                        .append("&maxResults=").append(maxResults)
                        .append("&key=").append(enc(youtubeApiKey));
                if (cat != null) {
                    url.append("&videoCategoryId=").append(enc(cat));
                }

                JsonNode root = MAPPER.readTree(httpGet(url.toString()));
                JsonNode items = root.path("items");
                if (items.isArray() && items.size() > 0) {
                    List<String> titles = new ArrayList<>();
                    for (JsonNode item : items) {
                        String title = item.path("snippet").path("title").asText("");
                        if (!title.isBlank()) {
                            titles.add(title);
                        }
                    }
                    if (!titles.isEmpty()) {
                        return titles;
                    }
                }
            } catch (Exception e) {
                lastError = e;
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // TikTok (Research API)
    // ------------------------------------------------------------------
    private static List<String> fetchTikTok(int limit) throws Exception {
        String token = tiktokAccessToken();
        String region = tiktokRegion.isBlank() ? "US" : tiktokRegion;
        int maxCount = Math.max(1, Math.min(limit * 2, 100));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String endDate = today.format(YYYYMMDD);
        String startDate = today.minusDays(7).format(YYYYMMDD);

        String url = "https://open.tiktokapis.com/v2/research/video/query/"
                + "?fields=id,video_description,view_count,hashtag_names,region_code";

        String requestBody = ("""
                {
                  "query": {
                    "and": [
                      { "operation": "EQ", "field_name": "region_code", "field_values": ["%s"] }
                    ]
                  },
                  "max_count": %d,
                  "start_date": "%s",
                  "end_date": "%s"
                }
                """).formatted(region, maxCount, startDate, endDate);

        JsonNode root = MAPPER.readTree(httpPostJson(url, requestBody, token));
        JsonNode videos = root.path("data").path("videos");

        List<Scored> scored = new ArrayList<>();
        if (videos.isArray()) {
            for (JsonNode video : videos) {
                long views = video.path("view_count").asLong(0);
                String text = clean(video.path("video_description").asText(""));
                if (text.isBlank()) {
                    text = clean(joinHashtags(video.path("hashtag_names")));
                }
                if (!text.isBlank()) {
                    scored.add(new Scored(views, shorten(text, 120)));
                }
            }
        }

        scored.sort(Comparator.comparingLong(Scored::views).reversed());

        List<String> topics = new ArrayList<>();
        for (Scored item : scored) {
            topics.add(item.text());
            if (topics.size() >= limit) {
                break;
            }
        }
        return topics;
    }

    private static String tiktokAccessToken() throws Exception {
        String form = "client_key=" + enc(tiktokClientKey)
                + "&client_secret=" + enc(tiktokClientSecret)
                + "&grant_type=client_credentials";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://open.tiktokapis.com/v2/oauth/token/"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cache-Control", "no-cache")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException(
                    "TikTok token HTTP " + response.statusCode() + ": " + truncate(response.body(), 300));
        }

        String token = MAPPER.readTree(response.body()).path("access_token").asText("");
        if (token.isBlank()) {
            throw new RuntimeException("TikTok token missing in response: " + truncate(response.body(), 300));
        }
        return token;
    }

    private static String joinHashtags(JsonNode hashtags) {
        if (!hashtags.isArray() || hashtags.size() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode tag : hashtags) {
            String name = tag.asText("");
            if (!name.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append('#').append(name);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // HTTP + text helpers
    // ------------------------------------------------------------------
    private static String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + truncate(response.body(), 300));
        }
        return response.body();
    }

    private static String httpPostJson(String url, String jsonBody, String bearerToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + truncate(response.body(), 300));
        }
        return response.body();
    }

    private static void addUnique(List<String> target, Set<String> seen, List<String> items) {
        for (String item : items) {
            String cleaned = clean(item);
            if (cleaned.isBlank()) {
                continue;
            }
            if (seen.add(cleaned.toLowerCase(Locale.ROOT))) {
                target.add(cleaned);
            }
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max).trim() + "…";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record Scored(long views, String text) {
    }
}
