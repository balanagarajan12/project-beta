package com.etp.migration.connector.source.sftp;

import com.etp.migration.connector.source.SftpSourceException;
import com.etp.migration.connector.source.csv.CsvPositionedFile;
import com.etp.migration.connector.source.csv.SourceByteProgress;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.PipelineTiming;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps one local copy of a remote CSV. Later batches in this process read that copy by source record number.
 * The copy is keyed by host, port, path, and CSV shape. A later process reuses it only when the remote size
 * and modification time match the stored stamp and the local bytes still match the stored SHA-256.
 * A same-size file with a different modification time is downloaded again. A same-size replacement that also
 * keeps the modification time is not detected. Host-key verification stays on the connection that checks or downloads.
 * An incomplete download is not published as the cached file.
 */
@Component
public class SftpSourceCache {

	private static final Logger log = LoggerFactory.getLogger(SftpSourceCache.class);

	private final SftpRemoteClient client;
	private final SftpCredentialResolver credentials;
	private final Path directory;
	private final ConcurrentHashMap<String, CsvPositionedFile> live = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

	public SftpSourceCache(
			SftpRemoteClient client,
			SftpCredentialResolver credentials,
			FileSystemArtifactStorage storage) {
		this.client = client;
		this.credentials = credentials;
		this.directory = storage.root().resolve("etp-source-cache").resolve("sftp");
	}

	public CsvPositionedFile materialize(SftpCsvSourceConfiguration config) {
		return materialize(config, null);
	}

	public CsvPositionedFile materialize(SftpCsvSourceConfiguration config, SourceByteProgress progress) {
		String key = key(config);
		CsvPositionedFile cached = live.get(key);
		if (cached != null) {
			PipelineTiming.increment("sftpCacheHits");
			return cached;
		}
		Object lock = locks.computeIfAbsent(key, ignored -> new Object());
		synchronized (lock) {
			cached = live.get(key);
			if (cached != null) {
				PipelineTiming.increment("sftpCacheHits");
				return cached;
			}
			CsvPositionedFile created = load(config, key, progress);
			live.put(key, created);
			return created;
		}
	}

	private CsvPositionedFile load(SftpCsvSourceConfiguration config, String key, SourceByteProgress progress) {
		Path dir = directory.resolve(key);
		Path csv = dir.resolve("source.csv");
		Path index = dir.resolve("rows.idx");
		Path stamp = dir.resolve("source.csv.stamp");
		Instant started = Instant.now();
		SftpCredential credential = credentials.resolve(config.credentialRef());
		SftpConnectionRequest request = new SftpConnectionRequest(
				config.host(),
				config.port(),
				credential.username(),
				credential.password(),
				config.remotePath(),
				config.credentialRef());
		long remoteSize;
		boolean reuse;
		Path partial = dir.resolve("source.csv.partial");
		SftpRemoteFile remote = client.open(request);
		try {
			remoteSize = remote.sizeBytes();
			reuse = reusable(csv, stamp, remoteSize, remote.modifiedAtMillis());
			if (!reuse) {
				Files.createDirectories(dir);
				Files.deleteIfExists(partial);
				log.info("SFTP download started host={} port={} remotePath={} bytes={}",
						config.host(), config.port(), config.remotePath(), remoteSize);
				String hash = copyComplete(remote, partial, remoteSize, progress);
				move(partial, csv);
				writeStamp(stamp, remoteSize, remote.modifiedAtMillis(), hash);
			}
		} catch (IOException ex) {
			throw SftpSourceFailures.translate(ex);
		} finally {
			closeQuietly(remote);
		}
		long transferMs = Duration.between(started, Instant.now()).toMillis();
		if (reuse) {
			PipelineTiming.increment("sftpCacheHits");
			log.info("SFTP source cache reused host={} port={} remotePath={} bytes={} durationMs={}",
					config.host(), config.port(), config.remotePath(), remoteSize, transferMs);
		} else {
			PipelineTiming.increment("sftpDownloads");
			PipelineTiming.add("sftpTransferMs", transferMs);
			log.info("SFTP source cached host={} port={} remotePath={} bytes={} durationMs={}",
					config.host(), config.port(), config.remotePath(), remoteSize, transferMs);
		}
		return CsvPositionedFile.open(
				csv,
				index,
				config.encoding(),
				config.delimiter(),
				config.quoteCharacter(),
				config.hasHeader(),
				progress);
	}

	private static boolean reusable(Path csv, Path stamp, long remoteSize, long modifiedAt) throws IOException {
		if (modifiedAt == SftpRemoteFile.UNKNOWN_MODIFIED_AT || !Files.isRegularFile(csv) || !Files.isRegularFile(stamp)) {
			return false;
		}
		if (Files.size(csv) != remoteSize) {
			return false;
		}
		Stamp stored = readStamp(stamp);
		if (stored == null || stored.size != remoteSize || stored.modifiedAt != modifiedAt) {
			return false;
		}
		return stored.sha256.equals(sha256(csv));
	}

	private static String copyComplete(
			SftpRemoteFile remote,
			Path partial,
			long remoteSize,
			SourceByteProgress progress) throws IOException {
		MessageDigest digest = sha256Digest();
		try (InputStream input = new DigestInputStream(counting(remote.stream(), progress), digest)) {
			Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException ex) {
			Files.deleteIfExists(partial);
			throw ex;
		}
		long copied = Files.size(partial);
		if (copied != remoteSize) {
			Files.deleteIfExists(partial);
			throw new SftpSourceException(
					MigrationErrorCodes.SFTP_READ_FAILED,
					"SFTP download ended after " + copied + " bytes; the server reported " + remoteSize);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void writeStamp(Path stamp, long size, long modifiedAt, String sha256) throws IOException {
		Path partial = stamp.resolveSibling(stamp.getFileName() + ".partial");
		Files.writeString(partial, size + "\n" + modifiedAt + "\n" + sha256 + "\n");
		move(partial, stamp);
	}

	private static Stamp readStamp(Path stamp) {
		try {
			String[] lines = Files.readString(stamp).split("\n", -1);
			if (lines.length < 3) {
				return null;
			}
			return new Stamp(Long.parseLong(lines[0].trim()), Long.parseLong(lines[1].trim()), lines[2].trim());
		} catch (IOException | NumberFormatException ex) {
			return null;
		}
	}

	private static String sha256(Path file) throws IOException {
		MessageDigest digest = sha256Digest();
		try (InputStream input = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static InputStream counting(InputStream input, SourceByteProgress progress) {
		if (progress == null) {
			return input;
		}
		return new java.io.FilterInputStream(input) {
			private long total;
			private long reported;

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException {
				int count = super.read(buffer, offset, length);
				advance(count);
				return count;
			}

			@Override
			public int read() throws IOException {
				int value = super.read();
				if (value >= 0) {
					advance(1);
				}
				return value;
			}

			private void advance(int count) {
				if (count <= 0) {
					return;
				}
				total += count;
				if (total - reported >= CsvPositionedFile.PROGRESS_BYTES) {
					reported = total;
					progress.advanced(SourceByteProgress.Stage.TRANSFER, total, -1L);
				}
			}
		};
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	private record Stamp(long size, long modifiedAt, String sha256) {
	}

	private static void move(Path partial, Path target) throws IOException {
		try {
			Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomic) {
			Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void closeQuietly(SftpRemoteFile remote) {
		try {
			remote.close();
		} catch (Exception ignored) {
			// A close race must not discard a copy that already reached disk.
		}
	}

	static String key(SftpCsvSourceConfiguration config) {
		String raw = config.host() + "\n" + config.port() + "\n" + config.remotePath()
				+ "\n" + config.delimiter() + "\n" + config.quoteCharacter()
				+ "\n" + config.hasHeader() + "\n" + config.encoding().name();
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}
}
