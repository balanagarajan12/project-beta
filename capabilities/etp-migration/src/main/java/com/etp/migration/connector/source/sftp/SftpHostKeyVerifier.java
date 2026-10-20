package com.etp.migration.connector.source.sftp;

import com.etp.migration.connector.source.SftpSourceException;
import com.etp.migration.processing.MigrationErrorCodes;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.sshd.client.config.hosts.KnownHostEntry;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trust policy for one SFTP connection. Pinned fingerprints for the credentialRef win over the
 * known_hosts file. {@code accept-unknown-host-keys} only applies to hosts with no trusted entry;
 * a key that contradicts a pin or a known_hosts entry is always rejected.
 */
final class SftpHostKeyVerifier implements ServerKeyVerifier {

	private static final Logger log = LoggerFactory.getLogger(SftpHostKeyVerifier.class);
	private static final Pattern SHA256_FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}=?");
	private static final String KNOWN_HOSTS_PROPERTY = "etp.migration.source.sftp.known-hosts-file";
	private static final String ACCEPT_UNKNOWN_PROPERTY = "etp.migration.source.sftp.accept-unknown-host-keys";

	private final String endpoint;
	private final String fingerprintProperty;
	private final List<String> pinnedFingerprints;
	private final ServerKeyVerifier knownHosts;
	private final boolean acceptUnknown;
	private volatile SftpSourceException rejection;

	private SftpHostKeyVerifier(
			String endpoint,
			String fingerprintProperty,
			List<String> pinnedFingerprints,
			Path knownHostsFile,
			boolean acceptUnknown) {
		this.endpoint = endpoint;
		this.fingerprintProperty = fingerprintProperty;
		this.pinnedFingerprints = pinnedFingerprints;
		this.acceptUnknown = acceptUnknown;
		this.knownHosts = knownHostsFile == null ? null : knownHostsVerifier(knownHostsFile);
	}

	static SftpHostKeyVerifier create(SftpCredentialProperties properties, SftpConnectionRequest request) {
		String endpoint = request.host() + ":" + request.port();
		String fingerprintProperty = "etp.migration.source.sftp.credentials."
				+ (request.credentialRef() == null ? "<credentialRef>" : request.credentialRef())
				+ ".host-key-fingerprints";
		List<String> pins = pinnedFingerprints(properties, request.credentialRef(), fingerprintProperty);
		Path knownHostsFile = pins.isEmpty() ? properties.getKnownHostsFile() : null;
		if (knownHostsFile != null) {
			validateKnownHosts(knownHostsFile);
		}
		if (pins.isEmpty() && knownHostsFile == null && !properties.isAcceptUnknownHostKeys()) {
			throw configurationError("SFTP host key verification is not configured for " + endpoint
					+ ". Set " + KNOWN_HOSTS_PROPERTY + " or " + fingerprintProperty
					+ ", or enable " + ACCEPT_UNKNOWN_PROPERTY + " for development only");
		}
		return new SftpHostKeyVerifier(
				endpoint, fingerprintProperty, pins, knownHostsFile, properties.isAcceptUnknownHostKeys());
	}

	@Override
	public boolean verifyServerKey(ClientSession session, SocketAddress remoteAddress, PublicKey serverKey) {
		String presented = fingerprint(serverKey);
		if (!pinnedFingerprints.isEmpty()) {
			if (pinnedFingerprints.contains(presented)) {
				return true;
			}
			return reject("SFTP host key for " + endpoint + " (" + presented + ") does not match "
					+ fingerprintProperty, "pin mismatch", presented);
		}
		if (knownHosts != null) {
			boolean accepted = knownHosts.verifyServerKey(session, remoteAddress, serverKey);
			if (!accepted && rejection == null) {
				reject("SFTP host key for " + endpoint + " (" + presented + ") is not accepted by the file configured by "
						+ KNOWN_HOSTS_PROPERTY, "rejected by known_hosts", presented);
			}
			return accepted;
		}
		return acceptUnknown(presented);
	}

	SftpSourceException rejection() {
		return rejection;
	}

	static String fingerprint(PublicKey key) {
		return stripPadding(KeyUtils.getFingerPrint(BuiltinDigests.sha256, key));
	}

	private boolean acceptUnknown(String presented) {
		if (acceptUnknown) {
			log.warn("Accepting untrusted SFTP host key endpoint={} fingerprint={} because {}=true",
					endpoint, presented, ACCEPT_UNKNOWN_PROPERTY);
			return true;
		}
		return reject("SFTP host key for " + endpoint + " (" + presented + ") is not trusted. Add it to the file configured by "
				+ KNOWN_HOSTS_PROPERTY + " or pin it with " + fingerprintProperty, "unknown host key", presented);
	}

	private boolean reject(String message, String reason, String presented) {
		log.warn("Rejected SFTP host key endpoint={} fingerprint={} reason={}", endpoint, presented, reason);
		rejection = new SftpSourceException(MigrationErrorCodes.SFTP_HOST_KEY_REJECTED, message);
		return false;
	}

	private ServerKeyVerifier knownHostsVerifier(Path file) {
		ServerKeyVerifier unknown = (session, remoteAddress, serverKey) -> acceptUnknown(fingerprint(serverKey));
		KnownHostsServerKeyVerifier verifier = new KnownHostsServerKeyVerifier(unknown, file) {
			@Override
			protected KnownHostEntry updateKnownHostsFile(
					ClientSession session,
					SocketAddress remoteAddress,
					PublicKey serverKey,
					Path knownHostsFile,
					Collection<HostEntryPair> knownHosts) {
				return null;
			}
		};
		verifier.setModifiedServerKeyAcceptor((session, remoteAddress, entry, expected, actual) -> reject(
				"SFTP host key for " + endpoint + " changed (" + fingerprint(actual) + ") and no longer matches the file configured by "
						+ KNOWN_HOSTS_PROPERTY + ". Confirm the new key with the SFTP administrator before updating it",
				"changed host key",
				fingerprint(actual)));
		return verifier;
	}

	private static List<String> pinnedFingerprints(
			SftpCredentialProperties properties,
			String credentialRef,
			String fingerprintProperty) {
		SftpCredentialProperties.Credential credential = credentialRef == null
				? null
				: properties.getCredentials().get(credentialRef);
		if (credential == null) {
			return List.of();
		}
		List<String> pins = new ArrayList<>();
		for (String value : credential.getHostKeyFingerprints()) {
			String pin = value == null ? "" : value.trim();
			if (pin.isEmpty()) {
				continue;
			}
			if (!SHA256_FINGERPRINT.matcher(pin).matches()) {
				throw configurationError(fingerprintProperty + " must contain OpenSSH SHA256:<base64> fingerprints");
			}
			pins.add(stripPadding(pin));
		}
		return List.copyOf(pins);
	}

	private static void validateKnownHosts(Path file) {
		if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
			log.warn("SFTP known_hosts file is missing or unreadable path={}", file);
			throw configurationError("The file configured by " + KNOWN_HOSTS_PROPERTY + " is missing or unreadable");
		}
		try {
			KnownHostEntry.readKnownHostEntries(file);
		} catch (Exception ex) {
			log.warn("SFTP known_hosts file could not be parsed path={}", file, ex);
			throw configurationError("The file configured by " + KNOWN_HOSTS_PROPERTY + " could not be parsed");
		}
	}

	private static SftpSourceException configurationError(String message) {
		return new SftpSourceException(MigrationErrorCodes.SFTP_HOST_KEY_CONFIGURATION_INVALID, message);
	}

	private static String stripPadding(String fingerprint) {
		return fingerprint.endsWith("=") ? fingerprint.substring(0, fingerprint.length() - 1) : fingerprint;
	}
}
