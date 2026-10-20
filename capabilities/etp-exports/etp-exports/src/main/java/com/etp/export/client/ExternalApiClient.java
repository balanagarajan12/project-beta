package com.etp.export.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.etp.export.config.ExportProperties;
import com.etp.export.dto.ExportRequest;
import com.etp.export.exception.ExportException;
import com.etp.export.exception.ExternalApiException;

/** Calls the external API described in an {@link ExportRequest}. */
@Component
public class ExternalApiClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiClient.class);
    private static final Set<HttpMethod> BODY_METHODS = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);

    private final RestClient restClient;
    private final ExportProperties props;

    public ExternalApiClient(RestClient exportRestClient, ExportProperties props) {
        this.restClient = exportRestClient;
        this.props = props;
    }

    public ExternalApiResponse call(ExportRequest req) {
        HttpMethod method = HttpMethod.valueOf(req.httpMethod().trim().toUpperCase(Locale.ROOT));
        URI uri = buildUri(req);
        checkAllowedHost(uri);

        log.info("Export call -> {} {}", method, uri);

        RestClient.RequestBodySpec spec = restClient.method(method)
                .uri(uri)
                .headers(h -> copyHeaders(req.headers(), h));

        if (BODY_METHODS.contains(method) && req.body() != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(req.body());
        }

        ResponseEntity<byte[]> entity = spec.retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw new ExternalApiException(response.getStatusCode(), body);
                })
                .toEntity(byte[].class);

        byte[] body = entity.getBody() == null ? new byte[0] : entity.getBody();
        return new ExternalApiResponse(body, entity.getHeaders().getContentType());
    }

    URI buildUri(ExportRequest req) {
        String path = req.path().trim();
        String full;
        if (path.matches("(?i)^https?://.*")) {
            full = path;
        } else if (props.defaultBaseUrl() != null && !props.defaultBaseUrl().isBlank()) {
            full = stripTrailingSlash(props.defaultBaseUrl().trim()) + (path.startsWith("/") ? path : "/" + path);
        } else {
            throw new ExportException(HttpStatus.BAD_REQUEST,
                    "path must be a full http(s) URL (or set etp.export.default-base-url)");
        }
        try {
            // already-encoded URL (e.g. contains %20) is used as is, otherwise it is encoded
            return UriComponentsBuilder.fromUriString(full).build(true).toUri();
        } catch (IllegalArgumentException notEncoded) {
            try {
                return UriComponentsBuilder.fromUriString(full).build().encode().toUri();
            } catch (IllegalArgumentException ex) {
                throw new ExportException(HttpStatus.BAD_REQUEST, "Invalid path URL: " + ex.getMessage(), ex);
            }
        }
    }

    private void checkAllowedHost(URI uri) {
        List<String> allowed = props.allowedHosts();
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean ok = allowed.stream()
                .map(a -> a.trim().toLowerCase(Locale.ROOT))
                .anyMatch(a -> a.startsWith("*.") ? host.endsWith(a.substring(1)) : host.equals(a));
        if (!ok) {
            throw new ExportException(HttpStatus.FORBIDDEN, "Host '" + host + "' is not in etp.export.allowed-hosts");
        }
    }

    private static void copyHeaders(Map<String, String> source, HttpHeaders target) {
        if (source == null) {
            return;
        }
        source.forEach((k, v) -> {
            if (k != null && v != null && !k.equalsIgnoreCase(HttpHeaders.HOST)
                    && !k.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)) {
                target.set(k, v);
            }
        });
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
