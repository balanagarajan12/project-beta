package com.etp.export.generator;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.etp.export.dto.FileType;

public class FileGeneratorFactory {

    private final Map<FileType, FileGenerator> generators = new EnumMap<>(FileType.class);

    public FileGeneratorFactory(List<FileGenerator> list) {
        list.forEach(g -> generators.put(g.fileType(), g));
    }

    public FileGenerator get(FileType type) {
        FileGenerator g = generators.get(type);
        if (g == null) {
            throw new IllegalStateException("No generator registered for " + type);
        }
        return g;
    }
}
