package com.agent.video;

import java.nio.file.Path;
import java.util.Map;

/** Uploads run artifacts to GCS. Returns logical name → gs:// URL. */
public interface MediaStore {
    Map<String, String> uploadRun(String runId, Map<String, Path> artifacts) throws Exception;
}
