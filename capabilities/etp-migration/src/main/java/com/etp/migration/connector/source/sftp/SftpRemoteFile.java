package com.etp.migration.connector.source.sftp;

import com.etp.migration.connector.source.SftpSourceException;
import com.etp.migration.processing.MigrationErrorCodes;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sshd.common.SshException;

public final class SftpRemoteFile implements AutoCloseable {

	static final long UNKNOWN_MODIFIED_AT = Long.MIN_VALUE;

	private final InputStream input;
	private final long sizeBytes;
	private final long modifiedAtMillis;
	private final Runnable onClose;
	private final AtomicBoolean closed = new AtomicBoolean();

	public SftpRemoteFile(InputStream input, long sizeBytes, Runnable onClose) {
		this(input, sizeBytes, UNKNOWN_MODIFIED_AT, onClose);
	}

	public SftpRemoteFile(InputStream input, long sizeBytes, long modifiedAtMillis, Runnable onClose) {
		this.input = input;
		this.sizeBytes = sizeBytes;
		this.modifiedAtMillis = modifiedAtMillis;
		this.onClose = onClose == null ? () -> { } : onClose;
	}

	public long sizeBytes() {
		return sizeBytes;
	}

	public long modifiedAtMillis() {
		return modifiedAtMillis;
	}

	public boolean isClosed() {
		return closed.get();
	}

	public InputStream stream() {
		return new FilterInputStream(input) {
			@Override
			public int read() throws IOException {
				return guard(super::read);
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException {
				return guard(() -> super.read(buffer, offset, length));
			}

			@Override
			public void close() throws IOException {
				SftpRemoteFile.this.close();
			}
		};
	}

	@Override
	public void close() throws IOException {
		if (closed.compareAndSet(false, true)) {
			IOException failure = null;
			try {
				input.close();
			} catch (IOException ex) {
				failure = ex;
			} catch (RuntimeException ex) {
				if (!executorShutdown(ex)) {
					throw ex;
				}
			} finally {
				onClose.run();
			}
			if (failure != null) {
				throw failure;
			}
		}
	}

	private static int guard(Reader reader) throws IOException {
		try {
			return reader.read();
		} catch (SftpSourceException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			if (executorShutdown(ex)) {
				throw new SftpSourceException(
						MigrationErrorCodes.SFTP_READ_FAILED,
						"SFTP read failed",
						ex);
			}
			throw ex;
		} catch (IOException ex) {
			String message = timedOut(ex)
					? "SFTP read timed out waiting for the server (etp.migration.source.sftp.idle-timeout)"
					: "SFTP read failed";
			throw new SftpSourceException(MigrationErrorCodes.SFTP_READ_FAILED, message, ex);
		}
	}

	static boolean executorShutdown(Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof IllegalStateException
					&& current.getMessage() != null
					&& current.getMessage().contains("Executor has been shut down")) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static boolean timedOut(IOException failure) {
		if (failure instanceof SocketTimeoutException) {
			return true;
		}
		return failure instanceof SshException
				&& failure.getMessage() != null
				&& failure.getMessage().startsWith("Timeout expired while waiting for id=");
	}

	@FunctionalInterface
	private interface Reader {
		int read() throws IOException;
	}
}
