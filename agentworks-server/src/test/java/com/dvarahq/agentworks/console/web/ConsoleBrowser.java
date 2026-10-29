package com.dvarahq.agentworks.console.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A browser-like Console client for tests: it keeps the session cookie, reads the CSRF token the way htmx does (from
 * the page's {@code hx-headers}), sends same-origin {@code Origin} headers, and marks actions as htmx requests. It
 * never follows redirects, so tests see every 302, 303, and {@code HX-Redirect}.
 */
final class ConsoleBrowser {
    private static final Pattern HX_HEADERS = Pattern.compile("hx-headers=\"\\{&quot;X-CSRF-Token&quot;:&quot;([^&]+)&quot;\\}\"");

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final String origin;
    private String cookie;
    private String csrf;

    ConsoleBrowser(int port) {
        this.origin = "http://localhost:" + port;
    }

    record Page(int status, String body, HttpHeaders headers) {
        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        /** Where the response sends the browser: {@code Location}, or {@code HX-Redirect} for an htmx action. */
        String redirect() {
            String location = header("Location");
            return location != null ? location.replaceFirst("^https?://[^/]+", "") : header("HX-Redirect");
        }
    }

    String cookie() {
        return cookie;
    }

    String csrf() {
        return csrf;
    }

    ConsoleBrowser cookie(String value) {
        this.cookie = value;
        return this;
    }

    ConsoleBrowser csrf(String token) {
        this.csrf = token;
        return this;
    }

    /** Signs in through the Console's own form. */
    Page signIn(String username, String password) {
        return form("POST", "/console/login", Map.of("username", username, "password", password), false,
                Map.of("Origin", origin));
    }

    Page get(String path) {
        return send(HttpRequest.newBuilder(URI.create(origin + path)).GET(), Map.of());
    }

    /** Loads a page and remembers its CSRF token, as htmx would read it from the page. */
    Page open(String path) {
        Page page = get(path);
        Matcher token = HX_HEADERS.matcher(page.body());
        if (token.find()) csrf = token.group(1);
        return page;
    }

    /** An htmx action: form-encoded, with the page's CSRF token and a same-origin {@code Origin}. */
    Page action(String path, Map<String, String> fields) {
        return form("POST", path, fields, true, Map.of("Origin", origin));
    }

    Page form(String method, String path, Map<String, String> fields, boolean htmx, Map<String, String> headers) {
        String body = fields.entrySet().stream().map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin + path))
                .method(method, "GET".equals(method) ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (!"GET".equals(method)) request.header("Content-Type", "application/x-www-form-urlencoded");
        Map<String, String> all = new LinkedHashMap<>();
        if (htmx) {
            all.put("HX-Request", "true");
            if (csrf != null) all.put("X-CSRF-Token", csrf);
        }
        all.putAll(headers);
        return send(request, all);
    }

    private Page send(HttpRequest.Builder request, Map<String, String> headers) {
        request.header("Accept", "text/html");
        if (cookie != null) request.header("Cookie", cookie);
        headers.forEach((name, value) -> {
            if (value != null) request.header(name, value);
        });
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                String pair = header.split(";", 2)[0];
                if (!pair.substring(0, pair.indexOf('=')).endsWith("agentworks-session")) continue;
                cookie = pair.endsWith("=") ? null : pair;
            }
            return new Page(response.statusCode(), response.body(), response.headers());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
