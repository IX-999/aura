package com.agent.video;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** YouTube publish-stage knobs, bound from application.properties (prefix "youtube.upload"). */
@ConfigurationProperties(prefix = "youtube.upload")
public record YoutubeUploadProperties(
        boolean enabled,
        String privacyStatus,
        String clientSecretsPath,
        String tokensDir,
        String descriptionSuffix) {
}
