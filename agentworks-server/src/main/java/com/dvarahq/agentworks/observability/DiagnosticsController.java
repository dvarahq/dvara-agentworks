package com.dvarahq.agentworks.observability;

import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** Authorized, tenant-bound, bounded operator diagnostics. */
@RestController
@RequestMapping("/api/v1/diagnostics")
public final class DiagnosticsController {
    private final WorkspaceScopeResolver workspaces;
    private final SupportBundleService bundles;

    public DiagnosticsController(WorkspaceScopeResolver workspaces, SupportBundleService bundles) {
        this.workspaces = workspaces;
        this.bundles = bundles;
    }

    @GetMapping(value = "/support-bundle", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> supportBundle(HttpServletRequest request,
            @RequestParam(name = "traceId", required = false) String traceId) {
        byte[] body = bundles.create(workspaces.resolve(request), traceId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentLength(body.length);
        headers.setContentDisposition(ContentDisposition.attachment().filename("agentworks-support-bundle.json",
                StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
