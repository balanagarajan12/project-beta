package com.etp.export.service;

import java.io.IOException;
import java.io.OutputStream;

import com.etp.export.dto.FileType;

/** File ready to be streamed to the client. */
public record ExportFile(String fileName, FileType fileType, ContentWriter writer) {

    @FunctionalInterface
    public interface ContentWriter {
        void writeTo(OutputStream out) throws IOException;
    }
}
