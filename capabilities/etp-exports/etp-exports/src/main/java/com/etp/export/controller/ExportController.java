package com.etp.export.controller;

import java.nio.charset.StandardCharsets;

import jakarta.validation.Valid;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.etp.export.dto.ExportRequest;
import com.etp.export.service.ExportFile;
import com.etp.export.service.ExportService;

@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    /** Calls the external API described in the body and returns the result as a downloadable CSV/XLSX file. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> export(@Valid @RequestBody ExportRequest request) {
        ExportFile file = exportService.export(request);

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.fileType().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // lets browser JS (React) read the file name
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .body(out -> file.writer().writeTo(out));
    }
}
