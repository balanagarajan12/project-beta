package com.etp.migration.connector.source.sftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.source.SftpSourceException;
import com.etp.migration.processing.MigrationErrorCodes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SftpIdleTimeoutTest {

	private static final String HOST = "127.0.0.1";
	private static final String USER = "sftp-user";
	private static final String PASSWORD = UUID.randomUUID().toString();
	private static final String CREDENTIAL_REF = "partner";
	private static final String SMALL = "/inbound/small.bin";
	private static final String LARGE = "/inbound/large.bin";

	private static Path root;
	private static SshServer server;
	private static int port;
	private static PublicKey serverKey;
	private static byte[] smallContent;

	private static volatile long readDelayMillis;
	private static volatile CountDownLatch stall;
	private static final AtomicInteger serverReads = new AtomicInteger();

	@BeforeAll
	static void startServer() throws Exception {
		root = Files.createTempDirectory("sftp-idle");
		Files.createDirectories(root.resolve("inbound"));
		smallContent = randomBytes(1024 * 1024, 1);
		Files.write(root.resolve("inbound/small.bin"), smallContent);
		Files.write(root.resolve("inbound/large.bin"), randomBytes(16 * 1024 * 1024, 2));
		SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider(root.resolve("host.ser"));
		keys.setAlgorithm("EC");
		keys.setKeySize(256);
		server = SshServer.setUpDefaultServer();
		server.setPort(0);
		server.setKeyPairProvider(keys);
		server.setPasswordAuthenticator((username, password, session) -> USER.equals(username) && PASSWORD.equals(password));
		server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder()
				.withFileSystemAccessor(new ControlledReads())
				.build()));
		server.setFileSystemFactory(new VirtualFileSystemFactory(root));
		server.start();
		port = server.getPort();
		serverKey = keys.loadKeys(null).iterator().next().getPublic();
	}

	@AfterAll
	static void stopServer() throws Exception {
		if (server != null) {
			server.stop();
		}
	}

	@BeforeEach
	void resetServerBehaviour() {
		readDelayMillis = 0;
		stall = null;
		serverReads.set(0);
	}

	@AfterEach
	void releaseAndCheckSessionsClosed() throws InterruptedException {
		CountDownLatch current = stall;
		if (current != null) {
			current.countDown();
		}
		for (int i = 0; i < 100 && !server.getActiveSessions().isEmpty(); i++) {
			Thread.sleep(50);
		}
		assertThat(server.getActiveSessions()).isEmpty();
	}

	@Test
	void stalledServerFailsTheReadAfterTheIdleTimeoutAndReleasesTheConnection() throws Exception {
		SftpCredentialProperties properties = trustedProperties(Duration.ofSeconds(2));
		stall = new CountDownLatch(1);
		SftpRemoteFile remote = new SshdSftpRemoteClient(properties).open(request(SMALL));
		long started = System.nanoTime();
		try {
			assertThatThrownBy(() -> remote.stream().read(new byte[1024]))
					.isInstanceOf(SftpSourceException.class)
					.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_READ_FAILED)
					.hasMessageContaining("timed out")
					.hasMessageNotContaining(PASSWORD);
		} finally {
			closeAsConnectorsDo(remote);
		}
		Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

		assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(1500)).isLessThan(Duration.ofSeconds(20));
		assertThat(remote.isClosed()).isTrue();
	}

	@Test
	void steadyTransferLongerThanTheIdleTimeoutCompletes() throws Exception {
		SftpCredentialProperties properties = trustedProperties(Duration.ofSeconds(1));
		assertThat(readFully(properties, SMALL)).isEqualTo(smallContent);
		int readsPerTransfer = serverReads.get();
		assertThat(readsPerTransfer).isGreaterThanOrEqualTo(4);
		readDelayMillis = Math.max(50, 3_000 / readsPerTransfer);
		assertThat(readDelayMillis).isLessThan(properties.getIdleTimeout().toMillis());

		long started = System.nanoTime();
		byte[] content = readFully(properties, SMALL);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

		assertThat(content).isEqualTo(smallContent);
		assertThat(elapsed).isGreaterThan(properties.getIdleTimeout());
	}

	@Test
	void consumerPauseLongerThanTheIdleTimeoutClosesTheSessionAndFailsTheRead() throws Exception {
		SftpCredentialProperties properties = trustedProperties(Duration.ofSeconds(1));
		SftpRemoteFile remote = new SshdSftpRemoteClient(properties).open(request(LARGE));
		try {
			InputStream input = remote.stream();
			assertThat(input.read(new byte[1024])).isPositive();
			Thread.sleep(4_000);

			assertThatThrownBy(input::readAllBytes)
					.isInstanceOf(SftpSourceException.class)
					.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_READ_FAILED)
					.hasMessageNotContaining(PASSWORD);
		} finally {
			closeAsConnectorsDo(remote);
		}
		assertThat(remote.isClosed()).isTrue();
	}

	private static void closeAsConnectorsDo(SftpRemoteFile remote) {
		try {
			remote.close();
		} catch (Exception ignored) {
			// the handle close fails once the session is gone; session and client teardown still run
		}
	}

	@Test
	void idleTimeoutMustBePositive() {
		SftpCredentialProperties properties = new SftpCredentialProperties();

		assertThat(properties.getIdleTimeout()).isEqualTo(Duration.ofMinutes(10));
		for (Duration invalid : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), null}) {
			assertThatThrownBy(() -> properties.setIdleTimeout(invalid))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("etp.migration.source.sftp.idle-timeout must be a positive duration");
		}
	}

	@Test
	void idleTimeoutBindsFromConfigurationAndInvalidValuesStopStartup() {
		ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class);

		runner.run(context -> assertThat(context.getBean(SftpCredentialProperties.class).getIdleTimeout())
				.isEqualTo(Duration.ofMinutes(10)));
		runner.withPropertyValues("etp.migration.source.sftp.idle-timeout=45s")
				.run(context -> assertThat(context.getBean(SftpCredentialProperties.class).getIdleTimeout())
						.isEqualTo(Duration.ofSeconds(45)));
		runner.withPropertyValues("etp.migration.source.sftp.idle-timeout=0s")
				.run(context -> assertThat(context).hasFailed().getFailure()
						.rootCause()
						.hasMessageContaining("etp.migration.source.sftp.idle-timeout must be a positive duration"));
	}

	@EnableConfigurationProperties(SftpCredentialProperties.class)
	static class PropertiesOnly {
	}

	private static SftpCredentialProperties trustedProperties(Duration idleTimeout) {
		SftpCredentialProperties properties = new SftpCredentialProperties();
		properties.setConnectTimeout(Duration.ofSeconds(5));
		properties.setIdleTimeout(idleTimeout);
		SftpCredentialProperties.Credential credential = new SftpCredentialProperties.Credential();
		credential.setUsername(USER);
		credential.setPassword(PASSWORD);
		credential.setHostKeyFingerprints(List.of(SftpHostKeyVerifier.fingerprint(serverKey)));
		properties.getCredentials().put(CREDENTIAL_REF, credential);
		return properties;
	}

	private static SftpConnectionRequest request(String path) {
		return new SftpConnectionRequest(HOST, port, USER, PASSWORD, path, CREDENTIAL_REF);
	}

	private static byte[] readFully(SftpCredentialProperties properties, String path) throws IOException {
		SftpRemoteFile remote = new SshdSftpRemoteClient(properties).open(request(path));
		try (InputStream input = remote.stream()) {
			return input.readAllBytes();
		}
	}

	private static byte[] randomBytes(int size, long seed) {
		byte[] bytes = new byte[size];
		new Random(seed).nextBytes(bytes);
		return bytes;
	}

	private static final class ControlledReads implements SftpFileSystemAccessor {

		@Override
		public SeekableByteChannel openFile(
				SftpSubsystemProxy subsystem,
				FileHandle fileHandle,
				Path file,
				String handle,
				Set<? extends OpenOption> options,
				FileAttribute<?>... attrs) throws IOException {
			SeekableByteChannel delegate = SftpFileSystemAccessor.super.openFile(subsystem, fileHandle, file, handle, options, attrs);
			return new SeekableByteChannel() {
				@Override
				public int read(ByteBuffer dst) throws IOException {
					serverReads.incrementAndGet();
					pause();
					return delegate.read(dst);
				}

				@Override
				public int write(ByteBuffer src) throws IOException {
					return delegate.write(src);
				}

				@Override
				public long position() throws IOException {
					return delegate.position();
				}

				@Override
				public SeekableByteChannel position(long newPosition) throws IOException {
					delegate.position(newPosition);
					return this;
				}

				@Override
				public long size() throws IOException {
					return delegate.size();
				}

				@Override
				public SeekableByteChannel truncate(long size) throws IOException {
					delegate.truncate(size);
					return this;
				}

				@Override
				public boolean isOpen() {
					return delegate.isOpen();
				}

				@Override
				public void close() throws IOException {
					delegate.close();
				}
			};
		}

		private static void pause() throws IOException {
			try {
				CountDownLatch current = stall;
				if (current != null) {
					current.await(30, TimeUnit.SECONDS);
				}
				long delay = readDelayMillis;
				if (delay > 0) {
					Thread.sleep(delay);
				}
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted", ex);
			}
		}
	}
}
