package com.agent.video;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Thumbnail;
import com.google.api.services.youtube.model.ThumbnailSetResponse;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes the finished video to YouTube: OAuth installed-app flow (one-time browser
 * consent, refresh token persisted to disk), resumable upload, then a best-effort
 * thumbnail set. See docs/VIDEO_PIPELINE.md.
 */
@Component
public class YouTubeUploader {

    private static final Logger log = LoggerFactory.getLogger(YouTubeUploader.class);
    private static final String APPLICATION_NAME = "Aura Channel";
    /**
     * youtube.upload alone cannot modify existing videos (videos.update returns
     * ACCESS_TOKEN_SCOPE_INSUFFICIENT — verified live); force-ssl covers upload,
     * update, and thumbnails.
     */
    private static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/youtube.upload",
            "https://www.googleapis.com/auth/youtube.force-ssl");
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final int LOCAL_RECEIVER_PORT = 8888;
    private static final int TITLE_MAX_LENGTH = 100;

    private final YoutubeUploadProperties props;

    public YouTubeUploader(YoutubeUploadProperties props) {
        this.props = props;
    }

    /**
     * Uploads {@code video} as an unlisted/private video (per {@code privacyStatus}), then
     * best-effort sets {@code thumbnail}. Returns {@code videoId}/{@code url}, plus
     * {@code thumbnailError} if the thumbnail set failed (unverified channels can't set
     * custom thumbnails — that must not fail the whole publish).
     */
    public Map<String, String> upload(Path video, Path thumbnail, String title, String description,
                                      String privacyStatus) throws Exception {
        YouTube youtube = buildYouTubeClient();

        VideoSnippet snippet = new VideoSnippet();
        snippet.setTitle(truncate(title, TITLE_MAX_LENGTH));
        snippet.setDescription(description);
        snippet.setCategoryId("27"); // Education

        VideoStatus status = new VideoStatus();
        status.setPrivacyStatus(privacyStatus);
        status.setSelfDeclaredMadeForKids(false);

        Video videoMetadata = new Video();
        videoMetadata.setSnippet(snippet);
        videoMetadata.setStatus(status);

        FileContent mediaContent = new FileContent("video/mp4", video.toFile());
        YouTube.Videos.Insert request = youtube.videos()
                .insert(List.of("snippet", "status"), videoMetadata, mediaContent);
        Video uploaded = request.execute();
        String videoId = uploaded.getId();
        log.info("uploaded video to YouTube: id={}", videoId);

        Map<String, String> result = new HashMap<>();
        result.put("videoId", videoId);
        result.put("url", "https://youtu.be/" + videoId);

        if (thumbnail != null) {
            try {
                FileContent thumbContent = new FileContent("image/jpeg", thumbnail.toFile());
                ThumbnailSetResponse thumbResponse = youtube.thumbnails().set(videoId, thumbContent).execute();
                log.info("set thumbnail for {}: {}", videoId, thumbResponse);
            } catch (Exception e) {
                // Unverified channels/apps can't set custom thumbnails — don't fail the publish.
                log.warn("thumbnail set failed for {}: {}", videoId, e.getMessage());
                result.put("thumbnailError", e.getMessage());
            }
        }

        return result;
    }

    /**
     * Updates an already-uploaded video's privacy status ("private" | "unlisted" | "public").
     * Uses the same cached OAuth credential as uploads (youtube.upload scope covers
     * videos.update).
     */
    public Map<String, String> setPrivacy(String videoId, String privacyStatus) throws Exception {
        YouTube youtube = buildYouTubeClient();

        Video video = new Video();
        video.setId(videoId);
        VideoStatus status = new VideoStatus();
        status.setPrivacyStatus(privacyStatus);
        status.setSelfDeclaredMadeForKids(false);
        video.setStatus(status);

        Video updated = youtube.videos().update(List.of("status"), video).execute();
        String resulting = updated.getStatus() != null ? updated.getStatus().getPrivacyStatus() : "unknown";
        log.info("set privacy of {} -> {}", videoId, resulting);

        Map<String, String> result = new HashMap<>();
        result.put("videoId", videoId);
        result.put("privacyStatus", resulting);
        return result;
    }

    private YouTube buildYouTubeClient() throws Exception {
        HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        Credential credential = authorize(httpTransport);
        return new YouTube.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private Credential authorize(HttpTransport httpTransport) throws Exception {
        Path secretsPath = Path.of(props.clientSecretsPath());
        GoogleClientSecrets clientSecrets;
        try (var reader = new InputStreamReader(new FileInputStream(secretsPath.toFile()), StandardCharsets.UTF_8)) {
            clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, reader);
        }

        Path tokensDir = Path.of(props.tokensDir() != null && !props.tokensDir().isBlank()
                ? props.tokensDir() : ".yt-tokens");
        java.nio.file.Files.createDirectories(tokensDir);

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(tokensDir.toFile()))
                .setAccessType("offline")
                .build();

        // First call opens a browser for one-time consent; the refresh token is then
        // persisted under tokensDir, so subsequent uploads are silent.
        return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver.Builder()
                .setPort(LOCAL_RECEIVER_PORT).build())
                .authorize("user");
    }

    private String truncate(String s, int maxLength) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLength ? s : s.substring(0, maxLength);
    }
}
