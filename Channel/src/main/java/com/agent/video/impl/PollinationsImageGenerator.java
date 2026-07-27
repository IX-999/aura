package com.agent.video.impl;

import com.agent.video.ImageGenerator;
import com.agent.video.VideoPipelineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Free image generation via Pollinations.ai (no API key; hosted open models, e.g. Flux).
 *
 * One HTTP GET per image: https://image.pollinations.ai/prompt/{prompt}?width=..&height=..
 * The service is rate-limited per IP (roughly one image every few seconds), so failures
 * are retried with a short backoff. Responses are validated as real images (magic bytes,
 * minimum size) before being written.
 */
public class PollinationsImageGenerator implements ImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(PollinationsImageGenerator.class);
    /** Authenticated endpoint (docs: gen.pollinations.ai/docs#tag/image; keys from enter.pollinations.ai/keys). */
    private static final String BASE_AUTH = "https://gen.pollinations.ai/image/";
    /** Legacy host still serves anonymous traffic (heavily rate-limited). */
    private static final String BASE_ANON = "https://image.pollinations.ai/prompt/";
    private static final int MIN_BYTES = 10_000;
    private static final int[] BACKOFF_SECONDS = {0, 5, 12, 25, 45};

    private final VideoPipelineProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public PollinationsImageGenerator(VideoPipelineProperties props) {
        this.props = props;
    }

    @Override
    public Path generate(String prompt, Path out) throws Exception {
        return generate(prompt, out, null);
    }

    /** modelOverride lets hero shots use a stronger model (e.g. gptimage) than the bulk default. */
    public Path generate(String prompt, Path out, String modelOverride) throws Exception {
        String fullPrompt = prompt + ", " + props.image().styleSuffix();
        int width = props.render() != null ? props.render().width() : 1920;
        int height = props.render() != null ? props.render().height() : 1080;

        String token = props.image().pollinationsToken();
        boolean authed = token != null && !token.isBlank();
        // Measured 2026-07-26 (1920x1080, authenticated): sana 2s (lower detail),
        // flux 7s (full quality), gptimage 21s. flux is the speed/quality sweet spot.
        String model = modelOverride != null && !modelOverride.isBlank()
                ? modelOverride.trim()
                : (props.image().pollinationsModel() != null
                        && !props.image().pollinationsModel().isBlank()
                        ? props.image().pollinationsModel().trim() : "flux");
        String url = (authed ? BASE_AUTH : BASE_ANON)
                + URLEncoder.encode(fullPrompt, StandardCharsets.UTF_8).replace("+", "%20")
                + "?width=" + width + "&height=" + height
                + "&model=" + model + "&nologo=true&private=true"
                + "&seed=" + ThreadLocalRandom.current().nextInt(1, 1_000_000_000);

        // Host failover: gen.pollinations.ai (authenticated, chosen model) and the legacy
        // image.pollinations.ai (anonymous, flux) run on SEPARATE infrastructure — verified
        // 2026-07-27, when the auth host degraded to ~5 img/min while legacy served in ~1s.
        // After two failed attempts, alternate hosts instead of pounding a sick one.
        String anonUrl = BASE_ANON
                + URLEncoder.encode(fullPrompt, StandardCharsets.UTF_8).replace("+", "%20")
                + "?width=" + width + "&height=" + height
                + "&model=flux&nologo=true&private=true"
                + "&seed=" + ThreadLocalRandom.current().nextInt(1, 1_000_000_000);

        Exception last = null;
        for (int attempt = 0; attempt < BACKOFF_SECONDS.length; attempt++) {
            if (BACKOFF_SECONDS[attempt] > 0) {
                Thread.sleep(BACKOFF_SECONDS[attempt] * 1000L);
            }
            boolean useAnon = authed && attempt >= 2 && attempt % 2 == 0;
            String attemptUrl = useAnon ? anonUrl : url;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(attemptUrl))
                        .timeout(Duration.ofSeconds(120))
                        .header("User-Agent", "aura-channel/1.0")
                        .GET();
                // Registered (free) tokens get a much higher rate tier than anonymous.
                // The legacy host takes no auth header.
                if (authed && !useAnon) {
                    builder.header("Authorization", "Bearer " + token.trim());
                }
                HttpRequest request = builder.build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                byte[] body = response.body();
                if (response.statusCode() == 200 && isImage(body)) {
                    Files.write(out, body);
                    log.info("Pollinations image{}: {} ({} KB)",
                            useAnon ? " [legacy-host fallback]" : "", out, body.length / 1024);
                    return out;
                }
                last = new IllegalStateException("Pollinations returned status "
                        + response.statusCode() + ", " + (body == null ? 0 : body.length) + " bytes");
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                last = e;
            }
            log.info("Pollinations retry {}/{} for {}: {}", attempt + 1, BACKOFF_SECONDS.length,
                    out.getFileName(), last.getMessage());
        }
        throw last;
    }

    private boolean isImage(byte[] body) {
        if (body == null || body.length < MIN_BYTES) {
            return false;
        }
        boolean jpeg = (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xD8;
        boolean png = (body[0] & 0xFF) == 0x89 && body[1] == 0x50;
        return jpeg || png;
    }
}
