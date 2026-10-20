package com.etp.export.generator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.etp.export.dto.FileType;
import com.etp.export.dto.TabularData;

/** RFC 4180 CSV, UTF-8, CRLF line endings. */
public class CsvFileGenerator implements FileGenerator {

    private static final String CRLF = "\r\n";
    private final boolean utf8Bom;

    public CsvFileGenerator(boolean utf8Bom) {
        this.utf8Bom = utf8Bom;
    }

    @Override
    public FileType fileType() {
        return FileType.CSV;
    }

    @Override
    public void generate(TabularData data, String sheetName, OutputStream out) throws IOException {
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 64 * 1024);
        if (utf8Bom) {
            w.write('﻿');
        }
        writeLine(w, data.headers());
        List<String> keys = data.keys();
        for (Map<String, Object> row : data.rows()) {
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    w.write(',');
                }
                Object v = row.get(keys.get(i));
                String text = CellValues.asText(v);
                w.write(escape(v instanceof Number || v instanceof Boolean ? text : CellValues.safeText(text)));
            }
            w.write(CRLF);
        }
        w.flush();
    }

    private static void writeLine(Writer w, List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                w.write(',');
            }
            w.write(escape(CellValues.safeText(values.get(i) == null ? "" : values.get(i))));
        }
        w.write(CRLF);
    }

    static String escape(String s) {
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
                || (!s.isEmpty() && (Character.isWhitespace(s.charAt(0)) || Character.isWhitespace(s.charAt(s.length() - 1))));
        return quote ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
