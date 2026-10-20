package com.etp.export.exception;

import org.springframework.http.HttpStatus;

/** Problem with the export request or the data returned by the external API. */
public class ExportException extends RuntimeException {

    private final HttpStatus status;

    public ExportException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ExportException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
