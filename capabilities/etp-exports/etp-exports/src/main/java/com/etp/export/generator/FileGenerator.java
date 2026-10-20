package com.etp.export.generator;

import java.io.IOException;
import java.io.OutputStream;

import com.etp.export.dto.FileType;
import com.etp.export.dto.TabularData;

/** Writes a {@link TabularData} in one file format. Add a new implementation + bean to support more formats. */
public interface FileGenerator {

    FileType fileType();

    void generate(TabularData data, String sheetName, OutputStream out) throws IOException;
}
