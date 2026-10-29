package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import com.dvarahq.agentworks.storage.identity.IdentityRows;
import com.dvarahq.agentworks.storage.identity.IdentityRows.BootstrapRow;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The single, time-bounded path to the first administrator of a clean installation.
 *
 * <p>At startup, while the installation has no users and no live token, one random bootstrap token is issued: its
 * digest is stored in the singleton {@code installation_bootstrap} row and the value is written to an owner-only file
 * whose path, but never the value, is logged. Presenting the token once creates the first organization administrator
 * with a password of their choosing and permanently completes the bootstrap. The token itself can never perform
 * administration, so administration always requires the rotated, personal credential.
 *
 * <p>Several nodes may share one database (#177). A live token is not replaced by any node: only an absent or expired
 * token is issued, at the next startup of whichever node starts first. The file is written while the issuing node
 * holds the bootstrap row's lock, before the digest commits, so on a shared volume the file always holds the stored
 * token, and an unwritable location commits nothing. The row records the issuing node and the file's path, and a node
 * that starts without the file logs where the live token is instead of reissuing it. The one exception is the
 * operator's single-use override {@code agentworks.security.bootstrap.reissue} (#185): the first node started with a
 * value not used before, whose own file does not hold the live token, replaces it (audited), and the value is recorded
 * as consumed so no later start of any node replaces the token with it again. A value replaces only the token that was
 * live when a node first saw it (#201), so a token issued later, by another node, is never replaced by it, and only on
 * a start that the operator confirmed with {@code agentworks.security.bootstrap.reissue-confirm}, the code a start with
 * the value logged (#206), so no replica or automatic restart that only has the value replaces it. The code is not a secret against the database: see {@link #reissueConfirmation}.
 */
public final class InstallationBootstrapService {
    private static final Logger log = LoggerFactory.getLogger(InstallationBootstrapService.class);

    public record Issued(Path tokenFile, Instant expiresAt) {}

    /** The created administrator and where it was placed. */
    public record Completed(UUID userId, String username, UUID organizationId, UUID workspaceId) {}

    private final TransactionTemplate transactions;
    private final JdbcIdentityRepository repository;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwords;
    private final CredentialCodec codec;
    private final SecurityPolicy.Bootstrap policy;
    private final AuthAuditLog audit;
    private final Clock clock;
    private final HashingBulkhead hashing;
    private final java.util.function.Supplier<String> nodeId;

    public InstallationBootstrapService(TransactionTemplate transactions, JdbcIdentityRepository repository,
            PasswordEncoder encoder, PasswordPolicy passwords, CredentialCodec codec, SecurityPolicy.Bootstrap policy,
            AuthAuditLog audit, Clock clock) {
        this(transactions, repository, encoder, passwords, codec, policy, audit, clock,
                new HashingBulkhead(SecurityPolicy.defaults().login().maxConcurrentVerifications()));
    }

    /** @param hashing the bulkhead shared with sign-in, bounding concurrent password hashing on this node */
    public InstallationBootstrapService(TransactionTemplate transactions, JdbcIdentityRepository repository,
            PasswordEncoder encoder, PasswordPolicy passwords, CredentialCodec codec, SecurityPolicy.Bootstrap policy,
            AuthAuditLog audit, Clock clock, HashingBulkhead hashing) {
        this(transactions, repository, encoder, passwords, codec, policy, audit, clock, hashing, null);
    }

    /**
     * @param nodeId this node's stable identity ({@code agentworks.node-id}), recorded with a token it issues or a reissue
     *     value it first sees or consumes (#185, #201); resolved only then. {@code null} uses the host name, which a container may change on every restart.
     */
    public InstallationBootstrapService(TransactionTemplate transactions, JdbcIdentityRepository repository,
            PasswordEncoder encoder, PasswordPolicy passwords, CredentialCodec codec, SecurityPolicy.Bootstrap policy,
            AuthAuditLog audit, Clock clock, HashingBulkhead hashing, java.util.function.Supplier<String> nodeId) {
        this.nodeId = nodeId == null ? InstallationBootstrapService::hostName : nodeId;
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.hashing = Objects.requireNonNull(hashing, "hashing");
    }

    public Path tokenFile() {
        return policy.tokenFile().toAbsolutePath();
    }

    /**
     * Issues the bootstrap token when the installation has no users and no live token; otherwise does nothing. A live
     * token is not replaced, whether or not this node has its file (#177): a node without it logs where the token was
     * written. The only exception is a reissue override value not consumed before, on a node whose file does not hold
     * the live token (#185), and confirmed by the operator with the code a start logged (#206); any value this node
     * starts with is recorded as consumed once it has been acted on (by a
     * replacement, by an issue, or by finding the live token in this node's file), so each value replaces the token at
     * most once across all nodes and starts. A value replaces only the token that was live when a node first saw it,
     * recorded before the value is acted on (#201), so no restart, after any failure, replaces a token another node
     * issued since. The file is written under the bootstrap row's lock, before the digest
     * commits; if the commit then fails, the file is removed again when it still holds this value. Once users exist, a
     * leftover token file is deleted. An unwritable location fails with a {@link BootstrapConfigurationException}
     * naming the property to set, and commits no token.
     */
    public Optional<Issued> prepare() {
        if (!policy.enabled()) return Optional.empty();
        Path file = tokenFile();
        record Prepared(Issued issued, BootstrapRow live, boolean raced) {}
        java.util.concurrent.atomic.AtomicReference<String> written = new java.util.concurrent.atomic.AtomicReference<>();
        String override = policy.reissue() == null ? null : reissueDigest(policy.reissue());
        if (override == null && policy.reissueConfirm() != null) {
            log.warn("agentworks.security.bootstrap.reissue-confirm is set without agentworks.security.bootstrap."
                    + "reissue and confirms nothing. Remove it.");
        }
        // Before the value is acted on, which token it may replace is fixed and committed (#201).
        boolean firstSeenNow = override != null && recordFirstSight(override);
        Prepared prepared;
        try {
            prepared = transactions.execute(status -> {
                if (repository.countUsers() > 0) return null;
                Instant now = clock.instant();
                Optional<BootstrapRow> current = repository.lockBootstrap();
                if (current.isPresent() && !"PENDING".equals(current.get().state())) return null;
                // Every value ever acted on is kept (V13), so two nodes left with two different values cannot take
                // turns replacing each other's token.
                boolean consumed = override != null && repository.bootstrapReissueConsumed(override);
                boolean reissued = false;
                if (current.isPresent() && current.get().expiresAt().isAfter(now)) {
                    // Live: it may be held by another node, so it is not replaced; it expires on its own. Only the
                    // operator's single-use override replaces it, for example when its file was lost with a container,
                    // and only once per value (#185): a value already consumed, by this node or any other, is ignored.
                    BootstrapRow live = current.get();
                    if (override == null) return new Prepared(null, live, false);
                    if (consumed) {
                        log.info("agentworks.security.bootstrap.reissue holds a value that was already used; the live "
                                + "bootstrap token is kept. Remove the setting.");
                        return new Prepared(null, live, false);
                    }
                    if (holdsLiveToken(file, live)) {
                        // Nothing to recover: this node's file holds the live token. The value is consumed, so no
                        // other node started with it replaces the token this file holds.
                        consumeWithoutReissue(override, now);
                        log.info("agentworks.security.bootstrap.reissue is set, but this node's file already holds the "
                                + "live bootstrap token, which is kept. Remove the setting.");
                        return new Prepared(null, live, false);
                    }
                    // A value replaces only the token that was live when any node first saw it (#201). A token issued
                    // after that, for example by the node that won the race to issue the first token while this one
                    // could not record its value as consumed, was issued legitimately and is kept; the value is
                    // consumed now.
                    Optional<IdentityRows.ReissueSighting> sighting = repository.bootstrapReissueSighting(override);
                    if (sighting.isEmpty() || !CredentialCodec.sameDigest(sighting.get().targetDigest(),
                            live.tokenDigest())) {
                        consumeWithoutReissue(override, now);
                        log.warn("agentworks.security.bootstrap.reissue holds a value first seen {}, when {}; the live "
                                + "bootstrap token issued by {} since then is kept, and the value is now consumed. "
                                + "To replace that token, set a new value. Remove the setting.",
                                sighting.map(seen -> "at " + seen.seenAt() + " by " + seen.seenBy()).orElse("earlier"),
                                sighting.map(seen -> seen.targetDigest() == null ? "no token was live"
                                        : "another token was live").orElse("its sighting was not recorded"),
                                live.issuedBy() == null ? "(unrecorded)" : live.issuedBy());
                        return new Prepared(null, live, false);
                    }
                    // The value replaces the live token only when the operator confirms it (#206): with the code this
                    // start logs, which names the value and that token, set as agentworks.security.bootstrap.
                    // reissue-confirm. Another replica started with the same value, or a restart by an orchestrator,
                    // has no code, so it never replaces the token: confirming needs the code from a log of this
                    // sighting, or read access to the bootstrap row (see reissueConfirmation).
                    // A value this start saw first never replaces a live token on the same start (#201 review): a
                    // value set before this build, or on a node of an earlier build during a rolling upgrade, was
                    // never recorded, so the token live now may have been issued after the operator set it.
                    String code = reissueConfirmation(policy.reissue(), live.tokenDigest());
                    boolean confirmed = policy.reissueConfirm() != null
                            && CredentialCodec.sameDigest(code, policy.reissueConfirm());
                    if (firstSeenNow || !confirmed) {
                        String issued = live.issuedBy() == null ? "(unrecorded)" : live.issuedBy();
                        if (policy.reissueConfirm() != null && !confirmed) {
                            log.warn("agentworks.security.bootstrap.reissue-confirm does not confirm this "
                                    + "agentworks.security.bootstrap.reissue value for the live bootstrap token issued "
                                    + "by {} at {}; the token is kept.", issued, live.issuedAt());
                        }
                        log.warn("agentworks.security.bootstrap.reissue is set: the live bootstrap token issued by {} "
                                + "at {} is kept on this start. To replace it, start one node that lacks its file "
                                + "again with the same value and agentworks.security.bootstrap.reissue-confirm={}. If "
                                + "that token was issued after you set the value, or should be kept, remove the "
                                + "setting instead.", issued, live.issuedAt(), code);
                        return new Prepared(null, live, false);
                    }
                    reissued = true;
                }
                String issuer = issuer();
                String value = codec.newValue(CredentialCodec.BOOTSTRAP_PREFIX);
                Instant expires = now.plus(policy.lifetime());
                try {
                    if (current.isEmpty()) {
                        repository.insertBootstrap(CredentialCodec.digest(value), now, expires, issuer, file.toString());
                    } else {
                        repository.reissueBootstrap(CredentialCodec.digest(value), now, expires, issuer, file.toString());
                    }
                    // A value this node starts with is consumed by any token it issues, so it cannot later replace
                    // the token that another node issued meanwhile.
                    if (override != null && !consumed) repository.consumeBootstrapReissue(override, now, issuer);
                } catch (DuplicateKeyException concurrent) {
                    // Another node issued the token first; it owns the only valid path.
                    status.setRollbackOnly();
                    return new Prepared(null, null, true);
                }
                if (reissued) {
                    // The live token is invalidated by the override: audited, and logged below.
                    audit.record("BOOTSTRAP_REISSUED", AuthAuditLog.SUCCEEDED, null, null, null, null, null, null, null,
                            null);
                    log.warn("agentworks.security.bootstrap.reissue is set to a value not used before, and confirmed: "
                            + "the live bootstrap token issued by {} is replaced and no longer valid. The value is now "
                            + "consumed and replaces no token again; remove both settings.",
                            current.get().issuedBy() == null ? "(unrecorded)" : current.get().issuedBy());
                } else {
                    audit.record("BOOTSTRAP_ISSUED", AuthAuditLog.SUCCEEDED, null, null, null, null, null, null, null,
                            null);
                }
                // Written while this transaction holds the row lock (or the new row), so no other node can issue, and
                // write, a token between this write and the commit.
                write(file, value);
                written.set(value);
                return new Prepared(new Issued(file, expires), null, false);
            });
        } catch (RuntimeException failure) {
            String value = written.get();
            if (value != null) deleteTokenFileHolding(value);
            throw failure;
        }
        if (prepared == null) {
            if (repository.countUsers() > 0) deleteTokenFile();
            return Optional.empty();
        }
        if (prepared.issued() == null) {
            if (prepared.raced() && override != null) consumeAfterLostRace(override);
            BootstrapRow live = prepared.raced() ? repository.findBootstrap().orElse(null) : prepared.live();
            logLiveToken(file, live);
            return Optional.empty();
        }
        Issued issued = prepared.issued();
        log.warn("No administrator exists. A one-time bootstrap token was written to {}; present it to "
                + "POST /api/v1/auth/bootstrap before {}.", issued.tokenFile(), issued.expiresAt());
        return Optional.of(issued);
    }

    /** Says where a live token that this node did not issue now was written; never its value. */
    private void logLiveToken(Path file, BootstrapRow live) {
        if (live == null || !"PENDING".equals(live.state())) return;
        if (holdsLiveToken(file, live)) {
            log.warn("No administrator exists. The one-time bootstrap token is in {}; present it to "
                    + "POST /api/v1/auth/bootstrap before {}.", file, live.expiresAt());
        } else {
            log.warn("No administrator exists. A one-time bootstrap token is live until {}; it was written by node {} "
                    + "to {} on that node, and this node does not replace it. Present it to POST /api/v1/auth/bootstrap, "
                    + "or restart any node after it expires to issue a new one.", live.expiresAt(),
                    live.issuedBy() == null ? "(unrecorded)" : live.issuedBy(),
                    live.tokenFile() == null ? "(unrecorded)" : live.tokenFile());
        }
    }

    /**
     * Whether this node's file holds the live token itself: its digest, not its path, is compared, since every node
     * may use the same path for a file that holds an older, expired token.
     */
    static boolean holdsLiveToken(Path file, BootstrapRow live) {
        try {
            if (!Files.exists(file)) return false;
            String value = Files.readString(file, StandardCharsets.US_ASCII).strip();
            return CredentialCodec.wellFormed(value, CredentialCodec.BOOTSTRAP_PREFIX)
                    && CredentialCodec.sameDigest(live.tokenDigest(), CredentialCodec.digest(value));
        } catch (IOException | SecurityException unreadable) {
            return false;
        }
    }

    /**
     * Records the first sight of a reissue override value (#201) in a transaction of its own, which commits before the
     * value is acted on: the digest of the token live now, the only token the value may ever replace, or none. A later
     * sighting, by this node's restart or by any other node, changes nothing. While the installation has users or a
     * completed bootstrap, nothing is recorded. A failure here fails startup before anything else is done.
     *
     * @return whether this start recorded the sighting
     */
    private boolean recordFirstSight(String override) {
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                if (repository.countUsers() > 0) return false;
                Optional<BootstrapRow> current = repository.lockBootstrap();
                if (current.isPresent() && !"PENDING".equals(current.get().state())) return false;
                if (repository.bootstrapReissueSighting(override).isPresent()) return false;
                Instant now = clock.instant();
                String target = current.filter(row -> row.expiresAt().isAfter(now)).map(BootstrapRow::tokenDigest)
                        .orElse(null);
                repository.recordBootstrapReissueSighting(override, now, issuer(), target);
                return true;
            }));
        } catch (DuplicateKeyException concurrent) {
            // Another node started with the same value saw it first, before any bootstrap row existed; its sighting
            // stands.
            return false;
        }
    }

    /**
     * After losing the race to issue the first token, records the value as consumed and audits it, in a transaction
     * of its own. This is best effort (#201): the value was first seen when no token was live, so it can never replace
     * the winner's token, and a later start consumes it. A failure is logged and, where the database allows, audited
     * as a failed {@code BOOTSTRAP_REISSUE_CONSUMED}; startup continues.
     */
    private void consumeAfterLostRace(String override) {
        try {
            transactions.executeWithoutResult(status -> {
                Optional<BootstrapRow> row = repository.lockBootstrap();
                if (row.isPresent() && "PENDING".equals(row.get().state())
                        && !repository.bootstrapReissueConsumed(override)) {
                    consumeWithoutReissue(override, clock.instant());
                }
            });
        } catch (RuntimeException failure) {
            log.warn("Another node issued the bootstrap token first, and the agentworks.security.bootstrap.reissue "
                    + "value could not be recorded as consumed ({}). It replaces no token: it was first seen when none "
                    + "was live, and the next start records it.", failure.getClass().getSimpleName());
            try {
                transactions.executeWithoutResult(status -> audit.record("BOOTSTRAP_REISSUE_CONSUMED",
                        AuthAuditLog.FAILED, null, null, null, null, null, null, null, "LOST_RACE"));
            } catch (RuntimeException unaudited) {
                log.warn("The failure to record the bootstrap reissue value as consumed could not be audited ({}).",
                        unaudited.getClass().getSimpleName());
            }
        }
    }

    /**
     * Records a reissue override value as consumed without replacing the token, under the bootstrap row's lock, and
     * audits it as {@code BOOTSTRAP_REISSUE_CONSUMED} (#185).
     */
    private void consumeWithoutReissue(String override, Instant now) {
        repository.consumeBootstrapReissue(override, now, issuer());
        audit.record("BOOTSTRAP_REISSUE_CONSUMED", AuthAuditLog.SUCCEEDED, null, null, null, null, null, null, null,
                null);
    }

    /** The recorded form of a reissue override value (#185): a digest, so no row holds the value itself. */
    static String reissueDigest(String value) {
        return CredentialCodec.digest("agentworks-bootstrap-reissue:" + value);
    }

    /**
     * The code that confirms replacing the token of {@code tokenDigest} with the reissue {@code value} (#206): the first
     * 16 hexadecimal digits of a digest of both. A start with the value logs it; an operator confirms by setting it as
     * {@code agentworks.security.bootstrap.reissue-confirm}. It confirms only that value for only that token, and it
     * reveals neither.
     *
     * <p>What it guarantees (#206 review): configuration alone cannot produce it, since it depends on the live token's
     * digest, which only the database holds, so a replica rolled out with the value, or an automatic restart, never
     * confirms. It is not a secret against the database: whoever can read {@code installation_bootstrap} (the Control
     * Plane role's {@code SELECT}) and knows the value can derive it. That reader is not what it guards against: the
     * same role may {@code UPDATE} a pending bootstrap row (V7_1) and so replace the token directly, which is why it is
     * not keyed with a server-held secret (the idempotency HMAC keys are optional, too). Nodes of a build before #206
     * do not ask for it and replace the token on their second start with the value.
     */
    public static String reissueConfirmation(String value, String tokenDigest) {
        return CredentialCodec.digest("agentworks-bootstrap-reissue-confirm:" + value + ":" + tokenDigest)
                .substring(0, 16);
    }

    /**
     * This node, as recorded with the token it issues: its configured {@code agentworks.node-id}, else its host name,
     * or {@code unknown-host}; at most 255 characters.
     */
    private String issuer() {
        String node = nodeId.get();
        if (node == null || node.isBlank()) node = hostName();
        node = node.strip();
        return node.length() > 255 ? node.substring(0, 255) : node;
    }

    /** The host name, the default node identity; a container may get a new one on every restart. */
    static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException | SecurityException unknown) {
            return "unknown-host";
        }
    }

    /**
     * After a failed commit, removes the token file only when it still holds {@code value} and the database does not
     * store that token: a commit whose acknowledgement was lost may still have stored it, and another node's token is
     * never deleted.
     */
    private void deleteTokenFileHolding(String value) {
        Path file = tokenFile();
        try {
            boolean stored;
            try {
                stored = repository.findBootstrap().filter(row -> "PENDING".equals(row.state()))
                        .map(row -> CredentialCodec.sameDigest(row.tokenDigest(), CredentialCodec.digest(value)))
                        .orElse(false);
            } catch (RuntimeException unknown) {
                // The database cannot say: keep the file rather than delete a token that may have committed.
                stored = true;
            }
            if (!stored && Files.exists(file) && Files.readString(file, StandardCharsets.US_ASCII).strip().equals(value)) {
                Files.deleteIfExists(file);
            }
        } catch (IOException | SecurityException ignored) {
            log.warn("The bootstrap token file {} could not be removed after its token failed to commit; it is not "
                    + "valid.", file);
        }
    }

    /** Thrown when the bootstrap token file cannot be written; names the property that relocates it. */
    public static final class BootstrapConfigurationException extends IllegalStateException {
        BootstrapConfigurationException(Path file, Exception cause) {
            super("The one-time bootstrap token cannot be written to " + file + " (" + cause.getClass().getSimpleName()
                    + "). Set agentworks.security.bootstrap.token-file to a writable path, or disable the bootstrap "
                    + "with agentworks.security.bootstrap.enabled=false.", cause);
        }
    }

    /**
     * Consumes the bootstrap token and creates the first organization administrator, who administers the default
     * workspace, or a new organization and workspace named in the request when no default workspace exists.
     * Every invalid, expired, consumed, or late attempt fails with the same {@code BOOTSTRAP_UNAVAILABLE}.
     */
    public Completed complete(String token, String username, String displayName, String password,
            String organizationName, String workspaceName) {
        String digest = CredentialCodec.wellFormed(token, CredentialCodec.BOOTSTRAP_PREFIX)
                ? CredentialCodec.digest(token) : null;
        Completed completed;
        try {
            // Unauthenticated callers never cause hashing: the token is checked first, without a lock and in
            // constant time, and a wrong token or a finished bootstrap is refused before the password is touched.
            Instant checked = clock.instant();
            repository.findBootstrap()
                    .filter(value -> "PENDING".equals(value.state()) && value.expiresAt().isAfter(checked))
                    .filter(value -> CredentialCodec.sameDigest(value.tokenDigest(), digest))
                    .orElseThrow(InstallationBootstrapService::unavailable);
            // The password is validated and hashed before the transaction, so no connection waits on the hash.
            String normalizedName = PasswordPolicy.normalizeUsername(username).orElse(null);
            String passwordHash = normalizedName != null && validPassword(normalizedName, password)
                    ? hashing.run(() -> encoder.encode(password)) : null;
            completed = transactions.execute(status -> {
                Instant now = clock.instant();
                repository.lockBootstrap()
                        .filter(value -> "PENDING".equals(value.state()) && value.expiresAt().isAfter(now))
                        .filter(value -> CredentialCodec.sameDigest(value.tokenDigest(), digest))
                        .orElseThrow(InstallationBootstrapService::unavailable);
                if (repository.countUsers() > 0) throw unavailable();
                String normalized = PasswordPolicy.normalizeUsername(username).orElseThrow(() -> new IdentityException(
                        IdentityException.INVALID_REQUEST,
                        "The username must be 3 to 64 lower-case letters, digits, or ._@+-."));
                passwords.validate(normalized, password);
                Objects.requireNonNull(passwordHash, "passwordHash");
                UUID organization;
                UUID workspace;
                if (repository.workspaceExists(DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value())) {
                    organization = DefaultTenantBootstrap.DEFAULT_ORGANIZATION_ID.value();
                    workspace = DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value();
                } else {
                    organization = UUID.randomUUID();
                    workspace = UUID.randomUUID();
                    repository.insertOrganization(organization, name(organizationName, "organization"));
                    repository.insertWorkspace(workspace, organization, name(workspaceName, "workspace"));
                }
                UUID user = UUID.randomUUID();
                repository.insertUser(user, organization, normalized, IdentityService.displayName(displayName, normalized),
                        IdentityService.ORGANIZATION_ADMIN, passwordHash, false, now);
                repository.insertMembership(workspace, organization, user, "ADMIN", now);
                if (!repository.completeBootstrap(user, now)) throw unavailable();
                repository.forgetBootstrapReissueTargets();
                audit.record("BOOTSTRAP_COMPLETED", AuthAuditLog.SUCCEEDED, organization, workspace, user,
                        "bootstrap-token", null, "USER", user, null);
                return new Completed(user, normalized, organization, workspace);
            });
        } catch (IdentityException rejected) {
            if (IdentityException.BOOTSTRAP_UNAVAILABLE.equals(rejected.code())) {
                transactions.executeWithoutResult(status -> audit.record("BOOTSTRAP_REJECTED", AuthAuditLog.DENIED,
                        null, null, null, "bootstrap-token", null, null, null, null));
            }
            throw rejected;
        }
        deleteTokenFile();
        return completed;
    }

    private boolean validPassword(String username, String password) {
        try {
            passwords.validate(username, password);
            return true;
        } catch (IdentityException rejected) {
            return false;
        }
    }

    private void deleteTokenFile() {
        try {
            Files.deleteIfExists(tokenFile());
        } catch (IOException | SecurityException ignored) {
            log.warn("The bootstrap token file {} could not be deleted; it is no longer valid.", tokenFile());
        }
    }

    private static String name(String value, String kind) {
        if (value == null || value.isBlank() || value.strip().length() > 200) {
            throw new IdentityException(IdentityException.INVALID_REQUEST,
                    "Without a default workspace, the bootstrap needs an " + kind + " name of at most 200 characters.");
        }
        return value.strip();
    }

    private static IdentityException unavailable() {
        return new IdentityException(IdentityException.BOOTSTRAP_UNAVAILABLE, "The installation bootstrap is not available.");
    }

    private static void write(Path file, String value) {
        try {
            Path directory = file.getParent();
            Files.createDirectories(directory);
            boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
            Path temporary = posix ? Files.createTempFile(directory, ".agentworks-bootstrap", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(directory, ".agentworks-bootstrap", ".tmp");
            Files.writeString(temporary, value + System.lineSeparator(), StandardCharsets.US_ASCII);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | SecurityException failure) {
            throw new BootstrapConfigurationException(file, failure);
        }
    }
}
