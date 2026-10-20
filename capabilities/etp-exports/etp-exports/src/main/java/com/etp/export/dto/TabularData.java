package com.etp.export.dto;

import java.util.List;
import java.util.Map;

/**
 * Format-neutral table built from the API response.
 *
 * @param headers column titles (same order as keys)
 * @param keys    flattened field keys used to read each row
 * @param rows    flattened records
 */
public record TabularData(List<String> headers, List<String> keys, List<Map<String, Object>> rows) {
}
