package com.etp.migration.connector.storage;

import java.time.Instant;

public record ArtifactMetadata(String objectKey, long sizeBytes, Instant lastModified) {
}
