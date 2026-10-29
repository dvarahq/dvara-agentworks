package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.identity.ApiTokenService;
import com.dvarahq.agentworks.identity.ApiTokenService.IssuedToken;
import com.dvarahq.agentworks.identity.IdentityException;
import com.dvarahq.agentworks.storage.identity.IdentityRows.TokenRow;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** API tokens of the principal's workspace. The token value appears only in the creation response. */
@RestController
@RequestMapping(path = "/api/v1/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
public final class ApiTokenController {
    public record CreateToken(String name, String role, Integer expiresInDays) {}

    public record TokenView(UUID id, String name, String prefix, String role, UUID workspaceId, UUID ownerId,
            Instant createdAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt) {
        static TokenView of(TokenRow row) {
            return new TokenView(row.id(), row.name(), row.prefix(), row.role(), row.workspaceId(), row.userId(),
                    row.createdAt(), row.expiresAt(), row.lastUsedAt(), row.revokedAt());
        }
    }

    /** Returned once at creation: {@code token} is never stored or shown again. */
    public record CreatedToken(TokenView metadata, String token) {
        @Override
        public String toString() {
            return "CreatedToken[id=" + metadata.id() + "]";
        }
    }


    private final ApiTokenService tokens;

    public ApiTokenController(ApiTokenService tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreatedToken> create(@AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestBody CreateToken body) {
        if (body.expiresInDays() != null && body.expiresInDays() < 1) {
            throw new IdentityException(IdentityException.INVALID_REQUEST, "expiresInDays must be positive.");
        }
        IssuedToken issued = tokens.issue(principal, body.name(), body.role(),
                body.expiresInDays() == null ? null : Duration.ofDays(body.expiresInDays()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(UriComponentsBuilder.fromPath("/api/v1/tokens/{id}").build(issued.row().id()))
                .body(new CreatedToken(TokenView.of(issued.row()), issued.token()));
    }

    /** The caller's tokens; {@code all=true} lists every member's tokens and requires token administration. */
    @GetMapping
    public Page<TokenView> list(@AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(name = "all", defaultValue = "false") boolean all) {
        // Identity collections are bounded and never paged.
        return Page.of(tokens.list(principal, all).stream().map(TokenView::of).toList());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        tokens.revoke(principal, id);
        return ResponseEntity.noContent().build();
    }
}
