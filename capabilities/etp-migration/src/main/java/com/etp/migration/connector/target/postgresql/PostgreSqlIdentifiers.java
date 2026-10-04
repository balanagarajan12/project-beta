package com.etp.migration.connector.target.postgresql;

import java.util.regex.Pattern;

public final class PostgreSqlIdentifiers {

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private PostgreSqlIdentifiers() {
	}

	public static boolean isValid(String value) {
		return value != null && IDENTIFIER.matcher(value).matches();
	}

	static String quote(String identifier) {
		if (!isValid(identifier)) {
			throw new IllegalArgumentException("PostgreSQL identifier is invalid");
		}
		return "\"" + identifier + "\"";
	}
}
