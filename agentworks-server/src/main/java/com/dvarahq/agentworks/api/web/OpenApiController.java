package com.dvarahq.agentworks.api.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Publishes the OpenAPI 3.1 description of the {@code /api/v1} contract (issue #35). The document is a versioned
 * source file ({@value #RESOURCE}), not generated at runtime, so it is reviewed with every contract change; the
 * contract tests fail when an endpoint, parameter, or response shape and the document disagree. It is public: it
 * describes the same interface the open-source code implements and grants nothing.
 */
@RestController
public final class OpenApiController {
    public static final String RESOURCE = "api/agentworks-api-v1.openapi.yaml";
    public static final String PATH = "/api/v1/openapi.yaml";
    public static final MediaType YAML = MediaType.parseMediaType("application/yaml");

    private final byte[] document;
    private final String etag;

    public OpenApiController() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            this.document = in.readAllBytes();
        } catch (IOException missing) {
            throw new UncheckedIOException("the OpenAPI description " + RESOURCE + " is missing", missing);
        }
        try {
            this.etag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document)) + "\"";
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    @GetMapping(PATH)
    public ResponseEntity<String> openApi() {
        return ResponseEntity.ok().contentType(YAML).eTag(etag).cacheControl(CacheControl.noCache())
                .body(new String(document, StandardCharsets.UTF_8));
    }
}
