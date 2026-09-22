package com.etp.migration.processing;

import java.util.Map;

public record SourceRecord(long recordNumber, Map<String, Object> fields) {
}
