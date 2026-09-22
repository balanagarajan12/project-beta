package com.etp.migration.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WorkerIdentity {

	private final String workerId;
	private final Duration leaseDuration;

	public WorkerIdentity(
			@Value("${etp.migration.worker.id:}") String configuredWorkerId,
			@Value("${etp.migration.worker.lease-duration:PT5M}") Duration leaseDuration) {
		this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
				? defaultWorkerId()
				: configuredWorkerId.trim();
		this.leaseDuration = leaseDuration;
	}

	public String workerId() {
		return workerId;
	}

	public Duration leaseDuration() {
		return leaseDuration;
	}

	private static String defaultWorkerId() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException ex) {
			host = "etp-migration";
		}
		return host + "-" + UUID.randomUUID();
	}
}
