package com.etp.migration.migration;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class ControlPlaneSupport {

	public static final int DEFAULT_PAGE = 0;
	public static final int DEFAULT_PAGE_SIZE = 20;
	public static final int MAX_PAGE_SIZE = 100;
	private ControlPlaneSupport() {
	}

	public static Map<String, Object> copyConfiguration(Map<String, Object> configuration) {
		if (configuration == null) {
			return new HashMap<>();
		}
		return new HashMap<>(configuration);
	}

	public static PageRequest pageRequest(int page, int size, Sort sort) {
		int safePage = Math.max(page, DEFAULT_PAGE);
		int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
		return PageRequest.of(safePage, safeSize, sort);
	}

	public static boolean isUniqueViolation(DataIntegrityViolationException ex, String constraintName) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
				return true;
			}
			String message = cause.getMessage();
			if (message != null && constraintName != null && message.contains(constraintName)) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}
}
