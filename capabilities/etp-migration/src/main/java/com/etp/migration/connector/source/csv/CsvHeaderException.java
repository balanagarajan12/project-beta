package com.etp.migration.connector.source.csv;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;

public class CsvHeaderException extends InvalidSourceConfigurationException {

	public CsvHeaderException(String message) {
		super(message);
	}
}
