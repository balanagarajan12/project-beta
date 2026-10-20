package com.etp.export.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.etp.export.dto.TabularData;

/**
 * Reads the external API response and builds the table.
 * <p>
 * Expected response:
 * <pre>
 * { "action": ..., "data": [ {...}, {...} ], "error": ..., "pagination": {...}, "status": ..., "timestamp": ... }
 * </pre>
 * Only {@code data} is used; action, error, pagination, status, timestamp etc. are ignored.
 * <ul>
 *   <li>Headers = keys of the objects inside {@code data}, in the order the API returns them
 *       (keys that appear only in later records are added at the end).</li>
 *   <li>Rows = one per object in {@code data}. If {@code data} is a single object it becomes one row.</li>
 *   <li>Nested objects become dotted headers: {"address":{"city":"X"}} -> "address.city".</li>
 *   <li>Arrays of values are joined with ", "; arrays of objects are written as JSON text.</li>
 * </ul>
 * Plain Java (no Spring) so it is easy to unit test.
 */
public class DataExtractor {

    public static final String DATA_KEY = "data";
    private static final String SCALAR_COLUMN = "value";

    private final Function<Object, String> complexValueSerializer;

    public DataExtractor(Function<Object, String> complexValueSerializer) {
        this.complexValueSerializer = complexValueSerializer;
    }

    public TabularData extract(Object response) {
        List<?> records = records(response);

        List<Map<String, Object>> rows = new ArrayList<>(records.size());
        Set<String> headers = new LinkedHashSet<>();
        for (Object rec : records) {
            Map<String, Object> flat = new LinkedHashMap<>();
            flatten(rec instanceof Map ? "" : SCALAR_COLUMN, rec, flat);
            headers.addAll(flat.keySet());
            rows.add(flat);
        }
        List<String> keys = List.copyOf(headers);
        return new TabularData(keys, keys, rows);
    }

    /** Takes only the "data" node of the response. A bare JSON array is also accepted. */
    private static List<?> records(Object response) {
        if (response == null) {
            return Collections.emptyList();
        }
        if (response instanceof List<?> list) {
            return list;
        }
        if (response instanceof Map<?, ?> map) {
            if (!map.containsKey(DATA_KEY)) {
                throw new IllegalArgumentException("External API response has no '" + DATA_KEY + "' field");
            }
            Object data = map.get(DATA_KEY);
            if (data == null) {
                return Collections.emptyList();
            }
            return data instanceof List<?> list ? list : List.of(data);
        }
        throw new IllegalArgumentException("External API response is not a JSON object or array");
    }

    private void flatten(String prefix, Object value, Map<String, Object> out) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty() && !prefix.isEmpty()) {
                out.put(prefix, null);
            }
            map.forEach((k, v) -> flatten(prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, v, out));
        } else if (value instanceof List<?> list) {
            boolean allScalar = list.stream().noneMatch(e -> e instanceof Map || e instanceof List);
            if (allScalar) {
                StringBuilder sb = new StringBuilder();
                for (Object e : list) {
                    if (!sb.isEmpty()) {
                        sb.append(", ");
                    }
                    sb.append(e == null ? "" : e);
                }
                out.put(prefix, sb.toString());
            } else {
                out.put(prefix, complexValueSerializer.apply(list));
            }
        } else {
            out.put(prefix, value);
        }
    }
}
