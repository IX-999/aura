package com.agent.video.impl;

import com.agent.video.VideoPipelineProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Generates images on a self-deployed SDXL-Lightning Vertex endpoint (dedicated L4 GPU).
 *
 * Why: gemini-2.5-flash-image runs on Dynamic Shared Quota (~5 req/min effective, no
 * adjustable limit), which made the image stage the pipeline's long pole. A dedicated
 * endpoint has no per-minute quota — throughput is whatever the GPU sustains (~1-2s/image).
 *
 * The endpoint is configured via {@code video.image.sdxl-endpoint} as the full resource
 * name ("projects/P/locations/L/endpoints/ID"). Auth is ADC (same credentials as the rest
 * of the pipeline). Response parsing is tolerant: any sufficiently long base64 string in
 * the prediction object is treated as the image payload, since Model Garden serving
 * containers differ in field naming.
 */
public class SdxlEndpointVisualService {

    private static final Logger log = LoggerFactory.getLogger(SdxlEndpointVisualService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** SDXL-friendly 16:9 bucket (multiples of 64). */
    private static final int WIDTH = 1344;
    private static final int HEIGHT = 768;

    /**
     * Full predict base URL (dedicated-endpoint DNS + /v1/ + resource name, no ":predict").
     * Dedicated endpoints must be called via their own DNS host, not the regional API host.
     */
    private final String endpointUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final GoogleCredentials credentials;

    public SdxlEndpointVisualService(String endpointUrl) throws Exception {
        this.endpointUrl = endpointUrl;
        this.credentials = GoogleCredentials.getApplicationDefault()
                .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
    }

    /** Generates one image and writes it to imageDir/seg-%03d.png. Throws on failure. */
    public Path generate(String prompt, String styleSuffix, int segmentIndex, Path imageDir) throws Exception {
        // This serving container reads size from the instance, not request-level parameters
        // (verified live: parameters-level height/width were ignored, instance-level honored).
        ObjectNode instance = MAPPER.createObjectNode();
        instance.put("prompt", prompt + ", " + styleSuffix);
        instance.put("negative_prompt",
                "photo, photorealistic, text, words, letters, watermark, logo, signature, blurry, low quality");
        instance.put("height", HEIGHT);
        instance.put("width", WIDTH);
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode instances = body.putArray("instances");
        instances.add(instance);

        credentials.refreshIfExpired();
        String token = credentials.getAccessToken().getTokenValue();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl + ":predict"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("SDXL endpoint returned HTTP " + response.statusCode()
                    + " for segment " + segmentIndex + ": " + truncate(response.body(), 300));
        }

        byte[] imageBytes = extractImageBytes(response.body(), segmentIndex);
        Path out = imageDir.resolve(String.format("seg-%03d.png", segmentIndex));
        Files.write(out, imageBytes);
        log.info("SDXL image: segment {} -> {}", segmentIndex, out);
        return out;
    }

    private byte[] extractImageBytes(String responseBody, int segmentIndex) throws Exception {
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode predictions = root.get("predictions");
        if (predictions != null && predictions.isArray() && predictions.size() > 0) {
            byte[] found = findBase64Image(predictions.get(0));
            if (found != null) {
                return found;
            }
        }
        throw new IllegalStateException("SDXL endpoint response had no decodable image for segment "
                + segmentIndex + ": " + truncate(responseBody, 300));
    }

    /** Recursively finds the first long base64 string that decodes to plausible image bytes. */
    private byte[] findBase64Image(JsonNode node) {
        if (node.isTextual() && node.asText().length() > 1000) {
            try {
                byte[] decoded = Base64.getDecoder().decode(node.asText());
                if (decoded.length > 1000) {
                    return decoded;
                }
            } catch (IllegalArgumentException ignored) {
                // not base64
            }
        }
        if (node.isArray() || node.isObject()) {
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                byte[] found = findBase64Image(it.next());
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String truncate(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
