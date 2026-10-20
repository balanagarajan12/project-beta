package com.etp.export.client;

import org.springframework.http.MediaType;

/** Raw response of the external API. contentType may be null. */
public record ExternalApiResponse(byte[] body, MediaType contentType) {
}
