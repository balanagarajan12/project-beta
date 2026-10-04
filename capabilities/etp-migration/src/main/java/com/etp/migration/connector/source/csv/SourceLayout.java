package com.etp.migration.connector.source.csv;

import java.util.List;

/**
 * Physical arrangement of CSV inputs. Entity names are configuration. This type does not resolve relationships.
 */
public record SourceLayout(SourceLayoutType type, List<SourceFileBinding> files) {

	public SourceLayout {
		files = files == null ? List.of() : List.copyOf(files);
	}

	public record SourceFileBinding(String entityName, CsvFileSourceConfiguration csv) {
	}
}
