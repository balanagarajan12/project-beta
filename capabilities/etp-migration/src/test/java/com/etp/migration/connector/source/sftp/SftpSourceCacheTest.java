package com.etp.migration.connector.source.sftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.source.SftpSourceException;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.processing.MigrationErrorCodes;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpSourceCacheTest {

	@TempDir
	Path tempDir;

	@Test
	void oneDownloadServesLaterBatchesAndARestartReusesTheSameBytes() throws Exception {
		byte[] body = "id,city\n1,Salem\n2,Bend\n".getBytes(StandardCharsets.UTF_8);
		CountingClient first = new CountingClient(body);
		FileSystemArtifactStorage storage = new FileSystemArtifactStorage(tempDir);
		storage.initializeRoot();
		SftpCsvSourceConfiguration config = configuration();

		SftpSourceCache cache = new SftpSourceCache(first, ref -> new SftpCredential("e2e", "secret-value"), storage);
		assertThat(cache.materialize(config).recordCount()).isEqualTo(2);
		assertThat(cache.materialize(config).recordCount()).isEqualTo(2);
		assertThat(first.opens).isEqualTo(1);
		assertThat(first.bytes.get()).isEqualTo(body.length);

		CountingClient restarted = new CountingClient(body);
		SftpSourceCache again = new SftpSourceCache(restarted, ref -> new SftpCredential("e2e", "secret-value"), storage);
		assertThat(again.materialize(config).recordCount()).isEqualTo(2);
		assertThat(restarted.opens).isEqualTo(1);
		assertThat(restarted.bytes.get()).isZero();
	}

	@Test
	void anIncompleteDownloadIsNotPublishedAsTheCachedFile() throws Exception {
		byte[] body = "id\n1\n".getBytes(StandardCharsets.UTF_8);
		CountingClient client = new CountingClient(body);
		client.reportedSize = 100;
		FileSystemArtifactStorage storage = new FileSystemArtifactStorage(tempDir);
		storage.initializeRoot();
		SftpSourceCache cache = new SftpSourceCache(client, ref -> new SftpCredential("e2e", "secret-value"), storage);

		assertThatThrownBy(() -> cache.materialize(configuration()))
				.isInstanceOf(SftpSourceException.class)
				.hasMessageContaining("ended after");
		try (var paths = Files.walk(tempDir)) {
			assertThat(paths.map(path -> path.getFileName().toString()).toList())
					.doesNotContain("source.csv", "source.csv.partial");
		}
	}

	@Test
	void sameSizeWithANewModificationTimeDownloadsAgain() {
		byte[] first = "id,city\n1,Salem\n2,Bend\n".getBytes(StandardCharsets.UTF_8);
		byte[] second = "id,city\n1,Baker\n2,Bend\n".getBytes(StandardCharsets.UTF_8);
		assertThat(second).hasSameSizeAs(first);
		FileSystemArtifactStorage storage = new FileSystemArtifactStorage(tempDir);
		storage.initializeRoot();
		SftpCsvSourceConfiguration config = configuration();
		CountingClient original = new CountingClient(first);
		new SftpSourceCache(original, ref -> new SftpCredential("e2e", "secret-value"), storage).materialize(config);

		CountingClient replaced = new CountingClient(second);
		replaced.modifiedAtMillis = original.modifiedAtMillis + 1;
		SftpSourceCache again = new SftpSourceCache(replaced, ref -> new SftpCredential("e2e", "secret-value"), storage);
		try (var rows = again.materialize(config).readAll()) {
			assertThat(rows.map(record -> String.valueOf(record.fields().get("city"))).toList())
					.containsExactly("Baker", "Bend");
		}
		assertThat(replaced.bytes.get()).isEqualTo(second.length);
	}

	@Test
	void executorShutdownBecomesAnSftpReadFailureAndCloseStillReleasesTheSession() throws Exception {
		assertThat(SftpSourceFailures.translate(new IllegalStateException("Executor has been shut down")))
				.isInstanceOf(com.etp.migration.connector.source.SftpSourceException.class)
				.hasMessage("SFTP read failed");

		AtomicInteger closed = new AtomicInteger();
		InputStream input = new InputStream() {
			@Override
			public int read() {
				return -1;
			}

			@Override
			public void close() {
				throw new IllegalStateException("Executor has been shut down");
			}
		};
		SftpRemoteFile remote = new SftpRemoteFile(input, 0, closed::incrementAndGet);
		remote.close();
		assertThat(closed.get()).isEqualTo(1);
		assertThat(remote.isClosed()).isTrue();
	}

	@Test
	void aTimedOutReadIsNotPublishedAsTheCachedFile() throws Exception {
		FileSystemArtifactStorage storage = new FileSystemArtifactStorage(tempDir);
		storage.initializeRoot();
		SftpRemoteClient stalled = request -> new SftpRemoteFile(new InputStream() {
			@Override
			public int read() {
				throw timedOut();
			}

			@Override
			public int read(byte[] buffer, int offset, int length) {
				throw timedOut();
			}
		}, 10, 1L, () -> { });
		SftpSourceCache cache = new SftpSourceCache(stalled, ref -> new SftpCredential("e2e", "secret-value"), storage);

		assertThatThrownBy(() -> cache.materialize(configuration()))
				.isInstanceOf(SftpSourceException.class)
				.hasMessageContaining("timed out");
		try (var paths = Files.walk(tempDir)) {
			assertThat(paths.map(path -> path.getFileName().toString()).toList())
					.doesNotContain("source.csv", "source.csv.partial");
		}
	}

	private static SftpSourceException timedOut() {
		return new SftpSourceException(
				MigrationErrorCodes.SFTP_READ_FAILED,
				"SFTP read timed out waiting for the server (etp.migration.source.sftp.idle-timeout)");
	}

	private static SftpCsvSourceConfiguration configuration() {
		return SftpCsvSourceConfiguration.from(Map.of(
				"provider", "SFTP",
				"fileFormat", "CSV",
				"host", "localhost",
				"port", 2222,
				"credentialRef", "e2e",
				"remotePath", "/upload/addresses.csv",
				"hasHeader", true));
	}

	private static final class CountingClient implements SftpRemoteClient {
		private final byte[] body;
		private int opens;
		private final AtomicInteger bytes = new AtomicInteger();
		private long modifiedAtMillis = 1_700_000_000_000L;
		private long reportedSize = -1;

		private CountingClient(byte[] body) {
			this.body = body;
		}

		@Override
		public SftpRemoteFile open(SftpConnectionRequest request) {
			opens++;
			InputStream input = new FilterInputStream(new ByteArrayInputStream(body)) {
				@Override
				public int read(byte[] buffer, int offset, int length) throws IOException {
					int count = super.read(buffer, offset, length);
					if (count > 0) {
						bytes.addAndGet(count);
					}
					return count;
				}
			};
			long size = reportedSize < 0 ? body.length : reportedSize;
			return new SftpRemoteFile(input, size, modifiedAtMillis, () -> { });
		}
	}
}
