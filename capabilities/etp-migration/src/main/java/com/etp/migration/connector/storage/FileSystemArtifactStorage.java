package com.etp.migration.connector.storage;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileSystemArtifactStorage {

	private final Path root;

	public FileSystemArtifactStorage(
			@Value("${etp.migration.storage.root:./data/migration}") Path storageRoot) {
		this.root = storageRoot.toAbsolutePath().normalize();
	}

	@PostConstruct
	public void initializeRoot() {
		try {
			Files.createDirectories(root);
		} catch (IOException ex) {
			throw new IllegalStateException("Unable to create migration storage root", ex);
		}
	}

	public Path root() {
		return root;
	}

	public InputStream open(String objectKey) {
		Path path = resolveExisting(objectKey);
		try {
			return Files.newInputStream(path);
		} catch (IOException ex) {
			throw new IllegalStateException("Unable to open source artifact", ex);
		}
	}

	public boolean exists(String objectKey) {
		return Files.isRegularFile(resolve(objectKey));
	}

	public ArtifactMetadata metadata(String objectKey) {
		Path path = resolveExisting(objectKey);
		try {
			return new ArtifactMetadata(
					objectKey,
					Files.size(path),
					Files.getLastModifiedTime(path).toInstant());
		} catch (IOException ex) {
			throw new IllegalStateException("Unable to read source artifact metadata", ex);
		}
	}

	public ArtifactMetadata write(String objectKey, InputStream content) {
		Path path = resolve(objectKey);
		try {
			Files.createDirectories(path.getParent());
			Files.copy(content, path);
			return new ArtifactMetadata(
					objectKey,
					Files.size(path),
					Instant.now());
		} catch (IOException ex) {
			throw new IllegalStateException("Unable to write source artifact", ex);
		}
	}

	Path resolveExisting(String objectKey) {
		Path path = resolve(objectKey);
		if (!Files.isRegularFile(path)) {
			throw new ArtifactNotFoundException(objectKey);
		}
		return path;
	}

	Path resolve(String objectKey) {
		if (objectKey == null || objectKey.isBlank()) {
			throw new InvalidArtifactPathException("Artifact object key is required");
		}
		String normalizedKey = objectKey.trim().replace('\\', '/');
		if (normalizedKey.startsWith("/") || normalizedKey.contains(":")) {
			throw new InvalidArtifactPathException("Artifact object key is not allowed");
		}
		Path relative;
		try {
			relative = Path.of(normalizedKey);
		} catch (RuntimeException ex) {
			throw new InvalidArtifactPathException("Artifact object key is not allowed");
		}
		if (relative.isAbsolute()) {
			throw new InvalidArtifactPathException("Artifact object key is not allowed");
		}
		Path resolved = root.resolve(relative).normalize();
		if (!resolved.startsWith(root) || root.relativize(resolved).startsWith("..")) {
			throw new InvalidArtifactPathException("Artifact object key is not allowed");
		}
		return resolved;
	}
}
