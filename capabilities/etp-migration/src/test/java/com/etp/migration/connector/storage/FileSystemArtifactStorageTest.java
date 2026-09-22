package com.etp.migration.connector.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStorageTest {

	@TempDir
	Path tempDir;

	private FileSystemArtifactStorage storage;

	@BeforeEach
	void setUp() {
		storage = new FileSystemArtifactStorage(tempDir);
		storage.initializeRoot();
	}

	@Test
	void opensFileUnderRoot() throws Exception {
		Path file = tempDir.resolve("samples").resolve("addresses.csv");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "a,b\n");

		assertThat(storage.exists("samples/addresses.csv")).isTrue();
		assertThat(storage.metadata("samples/addresses.csv").sizeBytes()).isEqualTo(4);
		assertThat(storage.open("samples/addresses.csv").readAllBytes()).isEqualTo("a,b\n".getBytes());
	}

	@Test
	void rejectsPathTraversal() {
		assertThatThrownBy(() -> storage.resolve("../../etc/passwd"))
				.isInstanceOf(InvalidArtifactPathException.class);
		assertThatThrownBy(() -> storage.resolve("/etc/passwd"))
				.isInstanceOf(InvalidArtifactPathException.class);
		assertThatThrownBy(() -> storage.resolve("C:/Windows/system32"))
				.isInstanceOf(InvalidArtifactPathException.class);
	}

	@Test
	void missingFileIsNotFound() {
		assertThat(storage.exists("missing.csv")).isFalse();
		assertThatThrownBy(() -> storage.open("missing.csv"))
				.isInstanceOf(ArtifactNotFoundException.class);
	}
}
