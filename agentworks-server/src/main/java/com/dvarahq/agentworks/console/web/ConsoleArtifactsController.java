package com.dvarahq.agentworks.console.web;

import com.dvarahq.agentworks.domain.artifact.ArtifactClassification;
import com.dvarahq.agentworks.domain.artifact.ArtifactPinKind;
import com.dvarahq.agentworks.domain.artifact.ArtifactValidation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Artifact discovery and lifecycle controls over the exact tenant-scoped REST controller used by API clients. */
@Controller
public final class ConsoleArtifactsController {
    private final ConsoleApi api;

    public ConsoleArtifactsController(ConsoleApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @GetMapping("/console/artifacts")
    public String artifacts(HttpServletRequest request,
            @RequestParam(name = "cursor", required = false) String cursor, Model model) {
        model.addAttribute("artifacts", api.artifacts(request, cursor));
        return "console/artifacts";
    }

    @GetMapping("/console/artifacts/{id}")
    public String artifact(HttpServletRequest request, @PathVariable UUID id, Model model) {
        model.addAttribute("artifact", api.artifact(request, id));
        model.addAttribute("pins", api.artifactPins(request, id));
        return "console/artifact";
    }

    @PostMapping("/console/artifacts")
    public ResponseEntity<Void> upload(HttpServletRequest request, @RequestParam("file") MultipartFile file,
            @RequestParam(name = "classification", defaultValue = "INTERNAL") ArtifactClassification classification,
            @RequestParam(name = "redacted", defaultValue = "false") boolean redacted) throws IOException {
        if (file.isEmpty()) throw ConsoleSupport.invalid("INVALID_ARTIFACT_REQUEST", "Choose a non-empty file.");
        String mediaType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        var artifact = api.uploadArtifact(request, mediaType, file.getBytes(), classification, redacted);
        return ConsoleSupport.redirect(request, "/console/artifacts/" + artifact.id());
    }

    @PostMapping("/console/artifacts/{id}/expire")
    public ResponseEntity<Void> expire(HttpServletRequest request, @PathVariable UUID id) {
        api.expireArtifact(request, id);
        return ConsoleSupport.redirect(request, "/console/artifacts/" + id);
    }

    @PostMapping("/console/artifacts/{id}/validation")
    public ResponseEntity<Void> validate(HttpServletRequest request, @PathVariable UUID id,
            @RequestParam ArtifactValidation validation, @RequestParam String reason) {
        api.validateArtifact(request, id, validation, reason);
        return ConsoleSupport.redirect(request, "/console/artifacts/" + id);
    }

    @PostMapping("/console/artifacts/{id}/pins")
    public ResponseEntity<Void> pin(HttpServletRequest request, @PathVariable UUID id,
            @RequestParam ArtifactPinKind kind, @RequestParam UUID subjectId) {
        api.pinArtifact(request, id, kind, subjectId);
        return ConsoleSupport.redirect(request, "/console/artifacts/" + id);
    }

    @PostMapping("/console/artifacts/{id}/pins/{pinId}/remove")
    public ResponseEntity<Void> unpin(HttpServletRequest request, @PathVariable UUID id, @PathVariable UUID pinId) {
        api.unpinArtifact(request, id, pinId);
        return ConsoleSupport.redirect(request, "/console/artifacts/" + id);
    }
}
