package com.etp.migration.connector.storage;

import com.etp.migration.entity.StorageProvider;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class ArtifactChecksumCalculator {

	private final ArtifactStorage artifactStorage;

	public ArtifactChecksumCalculator(ArtifactStorage artifactStorage) {
		this.artifactStorage = artifactStorage;
	}

	public String sha256(StorageProvider provider, String objectKey) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
		try (InputStream input = artifactStorage.open(provider, objectKey);
				DigestInputStream digestStream = new DigestInputStream(input, digest)) {
			digestStream.transferTo(OutputStream.nullOutputStream());
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new IllegalStateException("Unable to calculate source checksum", ex);
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
