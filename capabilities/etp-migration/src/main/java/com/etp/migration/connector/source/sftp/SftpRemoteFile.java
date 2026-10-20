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

	private final InputStream input;
	private final long sizeBytes;
	private final Runnable onClose;
	private final AtomicBoolean closed = new AtomicBoolean();

	public SftpRemoteFile(InputStream input, long sizeBytes, Runnable onClose) {
		this.input = input;
		this.sizeBytes = sizeBytes;
		this.onClose = onClose == null ? () -> { } : onClose;
	}

	public long sizeBytes() {
		return sizeBytes;
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
			try {
				input.close();
			} finally {
				onClose.run();
			}
		}
	}

	private static int guard(Reader reader) throws IOException {
		try {
			return reader.read();
		} catch (SftpSourceException ex) {
			throw ex;
		} catch (IOException ex) {
			String message = timedOut(ex)
					? "SFTP read timed out waiting for the server (etp.migration.source.sftp.idle-timeout)"
					: "SFTP read failed";
			throw new SftpSourceException(MigrationErrorCodes.SFTP_READ_FAILED, message, ex);
		}
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
