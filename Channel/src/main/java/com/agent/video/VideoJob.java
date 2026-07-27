package com.agent.video;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Mutable status record for one pipeline run, safe for concurrent reads. */
public class VideoJob {

    public enum Stage {
        QUEUED, PARSING, NARRATING, GENERATING_IMAGES, RENDERING, UPLOADING, UPLOADING_YOUTUBE, DONE, ERROR
    }

    private final String runId;
    private final String topic;
    private final Instant startedAt = Instant.now();
    private volatile Stage stage = Stage.QUEUED;
    private volatile String error;
    private final Map<String, String> artifacts = new ConcurrentHashMap<>();

    public VideoJob(String runId, String topic) {
        this.runId = runId;
        this.topic = topic;
    }

    public String runId() {
        return runId;
    }

    public String topic() {
        return topic;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Stage stage() {
        return stage;
    }

    public void stage(Stage s) {
        this.stage = s;
    }

    public String error() {
        return error;
    }

    public void fail(String message) {
        this.error = message;
        this.stage = Stage.ERROR;
    }

    public Map<String, String> artifacts() {
        return artifacts;
    }

    public void artifact(String name, String location) {
        artifacts.put(name, location);
    }
}
