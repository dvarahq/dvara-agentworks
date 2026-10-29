package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.identity.IdentityException;
import com.dvarahq.agentworks.identity.MembershipService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Members and roles of the principal's workspace. */
@RestController
@RequestMapping(path = "/api/v1/members", produces = MediaType.APPLICATION_JSON_VALUE)
public final class MembershipController {
    public record SetRole(String role) {}

    public record MemberView(UUID userId, String username, String displayName, String role, Instant createdAt) {}

    public record MembershipView(UUID userId, UUID workspaceId, String role) {}


    private final MembershipService memberships;

    public MembershipController(MembershipService memberships) {
        this.memberships = Objects.requireNonNull(memberships, "memberships");
    }

    @GetMapping
    public Page<MemberView> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        // Identity collections are bounded and never paged.
        return Page.of(memberships.list(principal).stream().map(row -> new MemberView(row.subjectId(),
                row.username(), row.displayName(), row.role(), row.createdAt())).toList());
    }

    /** Grants a role to a user of the workspace's organization, or changes it; 201 when newly granted. */
    @PutMapping(path = "/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MembershipView> put(@AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID userId, @RequestBody SetRole body) {
        WorkspaceRole role = WorkspaceRole.parse(body.role()).orElseThrow(() -> new IdentityException(
                IdentityException.INVALID_REQUEST, "The role must be ADMIN, AUTHOR, OPERATOR, or VIEWER."));
        boolean created = memberships.put(principal, userId, role);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new MembershipView(userId, principal.workspaceId().value(), role.name()));
    }

    /** Removes a membership together with the sessions and API tokens bound to it. */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID userId) {
        memberships.remove(principal, userId);
        return ResponseEntity.noContent().build();
    }
}
