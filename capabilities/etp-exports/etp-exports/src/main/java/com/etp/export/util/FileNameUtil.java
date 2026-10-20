package com.etp.export.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.etp.export.dto.FileType;

public final class FileNameUtil {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private FileNameUtil() {
    }

    /** Safe download name with the right extension, e.g. "orders_report.xlsx". */
    public static String build(String requested, FileType type) {
        String name = (requested == null || requested.isBlank())
                ? "export_" + LocalDateTime.now().format(TS)
                : requested.trim();

        String ext = "." + type.extension();
        if (name.toLowerCase(Locale.ROOT).endsWith(ext)) {
            name = name.substring(0, name.length() - ext.length());
        }
        // keep letters/digits (any language), dot, dash, underscore, space
        name = name.replaceAll("[^\\p{L}\\p{N}._\\- ]", "_").replaceAll("^\\.+", "");
        if (name.isBlank()) {
            name = "export_" + LocalDateTime.now().format(TS);
        }
        if (name.length() > 150) {
            name = name.substring(0, 150);
        }
        return name + ext;
    }
}
