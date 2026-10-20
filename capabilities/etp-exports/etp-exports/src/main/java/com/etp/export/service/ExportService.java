package com.etp.export.service;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import com.etp.export.client.ExternalApiClient;
import com.etp.export.client.ExternalApiResponse;
import com.etp.export.config.ExportProperties;
import com.etp.export.dto.ExportRequest;
import com.etp.export.dto.FileType;
import com.etp.export.dto.TabularData;
import com.etp.export.exception.ExportException;
import com.etp.export.generator.FileGenerator;
import com.etp.export.generator.FileGeneratorFactory;
import com.etp.export.util.FileNameUtil;

import tools.jackson.databind.json.JsonMapper;

/**
 * Flow: call external API -> (pass through if it already returned the requested file)
 * -> parse JSON -> flatten to table -> stream as CSV / XLSX.
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);
    private static final MediaType XLSX_MEDIA = MediaType.parseMediaType(FileType.XLSX.contentType());

    private final ExternalApiClient apiClient;
    private final DataExtractor extractor;
    private final FileGeneratorFactory generatorFactory;
    private final JsonMapper jsonMapper;
    private final ExportProperties props;

    public ExportService(ExternalApiClient apiClient, DataExtractor extractor,
                         FileGeneratorFactory generatorFactory, JsonMapper jsonMapper, ExportProperties props) {
        this.apiClient = apiClient;
        this.extractor = extractor;
        this.generatorFactory = generatorFactory;
        this.jsonMapper = jsonMapper;
        this.props = props;
    }

    public ExportFile export(ExportRequest req) {
        ExternalApiResponse response = apiClient.call(req);
        FileType type = req.fileType();
        String fileName = FileNameUtil.build(req.fileName(), type);

        if (isAlreadyInRequestedFormat(response, type)) {
            log.info("External API returned {} directly, passing through ({} bytes)", type, response.body().length);
            byte[] bytes = response.body();
            return new ExportFile(fileName, type, out -> out.write(bytes));
        }

        TabularData data = toTable(response, req);
        if (data.rows().size() > props.maxRows()) {
            throw new ExportException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Export has " + data.rows().size() + " rows, limit is " + props.maxRows());
        }
        log.info("Generating {} '{}' with {} rows x {} columns", type, fileName, data.rows().size(), data.keys().size());

        FileGenerator generator = generatorFactory.get(type);
        String sheet = (req.sheetName() == null || req.sheetName().isBlank()) ? props.defaultSheetName() : req.sheetName();
        return new ExportFile(fileName, type, out -> generator.generate(data, sheet, out));
    }

    private TabularData toTable(ExternalApiResponse response, ExportRequest req) {
        byte[] body = response.body();
        if (body.length == 0) {
            return extractor.extract(null);
        }
        Object json;
        try {
            json = jsonMapper.readValue(body, Object.class);
        } catch (RuntimeException ex) { // Jackson 3 exceptions are unchecked
            throw new ExportException(HttpStatus.BAD_GATEWAY,
                    "External API response is not valid JSON (content-type: " + response.contentType() + ")", ex);
        }
        try {
            return extractor.extract(json);
        } catch (IllegalArgumentException ex) {
            throw new ExportException(HttpStatus.BAD_GATEWAY, ex.getMessage(), ex);
        }
    }

    private static boolean isAlreadyInRequestedFormat(ExternalApiResponse response, FileType type) {
        MediaType ct = response.contentType();
        byte[] body = response.body();
        return switch (type) {
            case CSV -> ct != null && ct.isCompatibleWith(MediaType.parseMediaType("text/csv"));
            case XLSX -> ct != null && (ct.isCompatibleWith(XLSX_MEDIA)
                    || (ct.isCompatibleWith(MediaType.APPLICATION_OCTET_STREAM) && isZip(body)));
        };
    }

    /** XLSX files are ZIP archives starting with "PK". */
    private static boolean isZip(byte[] b) {
        return b.length > 3 && new String(b, 0, 2, StandardCharsets.ISO_8859_1).equals("PK");
    }
}
