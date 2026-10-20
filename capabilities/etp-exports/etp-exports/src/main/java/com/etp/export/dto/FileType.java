package com.etp.export.dto;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum FileType {

    CSV("csv", "text/csv; charset=UTF-8"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final String extension;
    private final String contentType;

    FileType(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** Accepts "csv", "CSV", "xlsx", ".xlsx" etc. */
    @JsonCreator
    public static FileType from(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (v.startsWith(".")) {
            v = v.substring(1);
        }
        for (FileType t : values()) {
            if (t.name().equals(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unsupported fileType '" + value + "'. Allowed: CSV, XLSX");
    }
}
