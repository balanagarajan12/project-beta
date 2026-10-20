package com.etp.export.exception;

import org.springframework.http.HttpStatusCode;

/** The external API answered with a 4xx/5xx status. */
public class ExternalApiException extends RuntimeException {

    private final HttpStatusCode upstreamStatus;
    private final String upstreamBody;

    public ExternalApiException(HttpStatusCode upstreamStatus, String upstreamBody) {
        super("External API returned " + upstreamStatus.value());
        this.upstreamStatus = upstreamStatus;
        this.upstreamBody = upstreamBody;
    }

    public HttpStatusCode getUpstreamStatus() {
        return upstreamStatus;
    }

    public String getUpstreamBody() {
        return upstreamBody;
    }
}
