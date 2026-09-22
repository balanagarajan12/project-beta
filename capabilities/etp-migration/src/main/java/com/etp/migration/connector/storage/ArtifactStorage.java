package com.etp.migration.connector.storage;

import com.etp.migration.entity.StorageProvider;
import java.io.InputStream;

public interface ArtifactStorage {

	InputStream open(StorageProvider provider, String objectKey);

	boolean exists(StorageProvider provider, String objectKey);

	ArtifactMetadata metadata(StorageProvider provider, String objectKey);

	ArtifactMetadata write(StorageProvider provider, String objectKey, InputStream content);
}
