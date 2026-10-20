package com.etp.migration.connector.source.sftp;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.SftpSourceException;
import com.etp.migration.processing.BatchProcessingException;
import com.etp.migration.processing.MigrationErrorCodes;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeoutException;
import org.apache.sshd.common.SshException;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

final class SftpSourceFailures {

	private SftpSourceFailures() {
	}

	static RuntimeException translate(Throwable failure) {
		if (failure instanceof SftpSourceException sftp) {
			return sftp;
		}
		if (failure instanceof InvalidSourceConfigurationException invalid) {
			return invalid;
		}
		if (failure instanceof BatchProcessingException batch) {
			Throwable cause = batch.getCause();
			if (cause instanceof SftpSourceException sftp) {
				return new BatchProcessingException(sftp.errorCode(), sftp.getMessage(), sftp);
			}
			if (isCsvParse(batch) || isCsvParse(cause)) {
				return new SftpSourceException(MigrationErrorCodes.CSV_PARSE_FAILED, "CSV parse failed", batch);
			}
			return batch;
		}
		SftpException sftp = find(failure, SftpException.class);
		if (sftp != null) {
			return switch (sftp.getStatus()) {
				case SftpConstants.SSH_FX_NO_SUCH_FILE -> new SftpSourceException(
						MigrationErrorCodes.SFTP_FILE_NOT_FOUND,
						"SFTP file was not found",
						sftp);
				case SftpConstants.SSH_FX_PERMISSION_DENIED -> new SftpSourceException(
						MigrationErrorCodes.SFTP_PERMISSION_DENIED,
						"SFTP permission denied",
						sftp);
				default -> new SftpSourceException(MigrationErrorCodes.SFTP_READ_FAILED, "SFTP read failed", sftp);
			};
		}
		if (isAuthentication(failure)) {
			return new SftpSourceException(
					MigrationErrorCodes.SFTP_AUTHENTICATION_FAILED,
					"SFTP authentication failed",
					failure);
		}
		if (isConnection(failure)) {
			return new SftpSourceException(
					MigrationErrorCodes.SFTP_CONNECTION_FAILED,
					"SFTP connection failed",
					failure);
		}
		if (failure instanceof IllegalArgumentException || isCsvParse(failure)) {
			return new SftpSourceException(MigrationErrorCodes.CSV_PARSE_FAILED, "CSV parse failed", failure);
		}
		if (failure instanceof IOException) {
			return new SftpSourceException(MigrationErrorCodes.SFTP_READ_FAILED, "SFTP read failed", failure);
		}
		if (failure instanceof RuntimeException runtime) {
			return runtime;
		}
		return new SftpSourceException(MigrationErrorCodes.SFTP_READ_FAILED, "SFTP read failed", failure);
	}

	private static boolean isAuthentication(Throwable failure) {
		String message = message(failure);
		return message.contains("auth") || message.contains("authentication");
	}

	private static boolean isConnection(Throwable failure) {
		if (find(failure, ConnectException.class) != null
				|| find(failure, UnresolvedAddressException.class) != null
				|| find(failure, TimeoutException.class) != null) {
			return true;
		}
		String message = message(failure);
		return message.contains("connection")
				|| message.contains("host key")
				|| message.contains("server key")
				|| message.contains("timed out")
				|| message.contains("timeout");
	}

	private static boolean isCsvParse(Throwable failure) {
		if (failure == null) {
			return false;
		}
		if (failure instanceof IllegalArgumentException && failure.getClass().getName().contains("csv")) {
			return true;
		}
		String type = failure.getClass().getName();
		return type.contains("commons.csv") || type.contains("CSV");
	}

	private static String message(Throwable failure) {
		StringBuilder text = new StringBuilder();
		Throwable current = failure;
		while (current != null) {
			if (current.getMessage() != null) {
				text.append(current.getMessage().toLowerCase());
			}
			if (current instanceof SshException) {
				text.append(" ssh");
			}
			current = current.getCause();
		}
		return text.toString();
	}

	private static <T extends Throwable> T find(Throwable failure, Class<T> type) {
		Throwable current = failure;
		while (current != null) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
			current = current.getCause();
		}
		return null;
	}
}
