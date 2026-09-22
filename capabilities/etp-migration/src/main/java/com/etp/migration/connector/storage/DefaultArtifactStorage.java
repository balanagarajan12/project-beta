package com.etp.migration.connector.storage;

import com.etp.migration.entity.StorageProvider;
import java.io.InputStream;
import org.springframework.stereotype.Component;

@Component
public class DefaultArtifactStorage implements ArtifactStorage {

	private final FileSystemArtifactStorage fileSystemArtifactStorage;

	public DefaultArtifactStorage(FileSystemArtifactStorage fileSystemArtifactStorage) {
		this.fileSystemArtifactStorage = fileSystemArtifactStorage;
	}

	@Override
	public InputStream open(StorageProvider provider, String objectKey) {
		return fileSystem(provider).open(objectKey);
	}

	@Override
	public boolean exists(StorageProvider provider, String objectKey) {
		return fileSystem(provider).exists(objectKey);
	}

	@Override
	public ArtifactMetadata metadata(StorageProvider provider, String objectKey) {
		return fileSystem(provider).metadata(objectKey);
	}

	@Override
	public ArtifactMetadata write(StorageProvider provider, String objectKey, InputStream content) {
		return fileSystem(provider).write(objectKey, content);
	}

	private FileSystemArtifactStorage fileSystem(StorageProvider provider) {
		if (provider != StorageProvider.FILE_SYSTEM) {
			throw new UnsupportedStorageProviderException(provider);
		}
		return fileSystemArtifactStorage;
	}
}
