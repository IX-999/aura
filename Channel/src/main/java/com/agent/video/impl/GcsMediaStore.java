package com.agent.video.impl;

import com.agent.video.MediaStore;
import com.agent.video.VideoPipelineProperties;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Uploads run artifacts to GCS. See docs/VIDEO_PIPELINE.md. */
@Component
public class GcsMediaStore implements MediaStore {

    private static final Logger log = LoggerFactory.getLogger(GcsMediaStore.class);

    private final VideoPipelineProperties props;
    private final Storage storage;

    public GcsMediaStore(VideoPipelineProperties props,
                         @Value("${google.cloud.project-id}") String projectId) {
        this.props = props;
        // User-credential ADC carries no default project, so it must be set explicitly.
        this.storage = StorageOptions.newBuilder().setProjectId(projectId).build().getService();
    }

    @Override
    public Map<String, String> uploadRun(String runId, Map<String, Path> artifacts) throws Exception {
        String bucketName = props.gcs().bucket();
        ensureBucket(bucketName);

        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Path> entry : artifacts.entrySet()) {
            String name = entry.getKey();
            Path path = entry.getValue();
            String objectName = "runs/" + runId + "/" + name;

            com.google.cloud.storage.BlobId blobId = com.google.cloud.storage.BlobId.of(bucketName, objectName);
            com.google.cloud.storage.BlobInfo blobInfo = com.google.cloud.storage.BlobInfo.newBuilder(blobId).build();
            storage.createFrom(blobInfo, path);

            String gsUrl = "gs://" + bucketName + "/" + objectName;
            result.put(name, gsUrl);
            log.info("uploaded {} -> {}", path, gsUrl);
        }

        return result;
    }

    private void ensureBucket(String bucketName) {
        Bucket bucket = storage.get(bucketName);
        if (bucket == null) {
            log.info("bucket {} does not exist, creating (US multi-region)", bucketName);
            storage.create(BucketInfo.newBuilder(bucketName).setLocation("US").build());
        }
    }
}
