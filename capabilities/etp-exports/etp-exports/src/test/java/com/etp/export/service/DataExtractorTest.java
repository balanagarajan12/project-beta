package com.etp.export.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.etp.export.dto.TabularData;

class DataExtractorTest {

    private final DataExtractor extractor = new DataExtractor(String::valueOf);

    private static Map<String, Object> location(String id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("status", "ESTABLISHED");
        m.put("externalId", null);
        m.put("contextCount", 2);
        return m;
    }

    @Test
    void usesOnlyDataKeysAsHeaders() {
        Map<String, Object> response = new HashMap<>();
        response.put("action", "listLocations");
        response.put("data", List.of(location("BLDG-010001", "Cedar Point Apartments"),
                location("BLDG-010002", "Pine Valley Apartments")));
        response.put("error", null);
        response.put("pagination", Map.of("totalCount", 51551, "offset", 0, "limit", 20));
        response.put("status", "SUCCESS");
        response.put("timestamp", "2026-10-05T15:22:35Z");

        TabularData t = extractor.extract(response);

        assertThat(t.headers()).containsExactly("id", "name", "status", "externalId", "contextCount");
        assertThat(t.rows()).hasSize(2);
        assertThat(t.rows().get(1)).containsEntry("name", "Pine Valley Apartments");
    }

    @Test
    void nullDataGivesEmptyTable() {
        Map<String, Object> response = new HashMap<>();
        response.put("data", null);
        assertThat(extractor.extract(response).rows()).isEmpty();
    }

    @Test
    void missingDataFails() {
        assertThatThrownBy(() -> extractor.extract(Map.of("status", "SUCCESS")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
