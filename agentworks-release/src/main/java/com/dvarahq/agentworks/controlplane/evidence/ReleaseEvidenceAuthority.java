package com.dvarahq.agentworks.controlplane.evidence;

import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Bundle;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Ed25519Signer;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.EvidenceReference;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Payload;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.TrustPolicy;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Verification;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleConflictException;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleNotFoundException;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseBundleRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRow;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.DatabaseClock;
import com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Spring-JDBC authority for signing, importing, exporting, verifying, and admitting release bundles. */
public final class ReleaseEvidenceAuthority implements ReleaseAdmissionPolicy {
    private final TenantTransactionTemplate tenants;
    private final JdbcLifecycleRepository lifecycle;
    private final DatabaseClock clock;
    private final Ed25519Signer signer;
    private final TrustPolicy trust;
    private final EvidenceAnchor anchor;

    public ReleaseEvidenceAuthority(TenantTransactionTemplate tenants, JdbcLifecycleRepository lifecycle,
            DatabaseClock clock, Ed25519Signer signer, TrustPolicy trust, EvidenceAnchor anchor) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.signer = signer;
        this.trust = Objects.requireNonNull(trust, "trust");
        this.anchor = anchor == null ? EvidenceAnchor.none() : anchor;
    }

    /** Signs once. Replays return the immutable stored bundle; key rotation never rewrites release history. */
    public Bundle sign(WorkspaceId workspace, UUID releaseId) {
        return tenants.execute(workspace, () -> {
            var existing = lifecycle.findReleaseBundle(releaseId);
            if (existing.isPresent()) return validateStored(existing.get());
            if (signer == null) {
                throw conflict("SIGNING_AUTHORITY_UNAVAILABLE", "no Control Plane signing key is configured");
            }
            ReleaseRow release = lifecycle.findRelease(releaseId).orElseThrow(LifecycleNotFoundException::new);
            List<EvidenceReference> evidence = evidence(release.manifestJson());
            Payload payload = new Payload(ReleaseEvidence.BUNDLE_VERSION, release.id(), release.manifestJson(),
                    release.manifestDigest(), evidence, null);
            Instant now = clock.now();
            Bundle bundle = signer.sign(payload, now);
            Verification verified = trust.verify(bundle);
            if (!verified.trusted()) throw conflict(verified.code(), verified.detail());
            lifecycle.insertReleaseBundle(new ReleaseBundleRow(workspace.value(), release.id(), bundle.canonicalJson(),
                    bundle.digest(), release.manifestDigest(), bundle.signature().keyId(), bundle.signature().signedAt(), now));
            return bundle;
        });
    }

    public Bundle exportBundle(WorkspaceId workspace, UUID releaseId) {
        return tenants.execute(workspace, () -> lifecycle.findReleaseBundle(releaseId).map(this::validateStored)
                .orElseThrow(LifecycleNotFoundException::new));
    }

    /** Imports only against the exact existing release identity and manifest. It never reconstructs mutable inputs. */
    public Bundle importBundle(WorkspaceId workspace, String json) {
        Bundle bundle;
        try {
            bundle = ReleaseEvidence.parse(json);
        } catch (RuntimeException invalid) {
            throw conflict("BUNDLE_INVALID", "the portable release bundle is invalid");
        }
        Verification verified = trust.verify(bundle);
        if (!verified.trusted()) throw conflict(verified.code(), verified.detail());
        return tenants.execute(workspace, () -> {
            ReleaseRow release = lifecycle.findRelease(bundle.payload().releaseId())
                    .orElseThrow(LifecycleNotFoundException::new);
            if (!release.manifestDigest().equals(bundle.payload().manifestDigest())
                    || !release.manifestJson().equals(bundle.payload().manifest())) {
                throw conflict("RELEASE_BUNDLE_MISMATCH", "the bundle does not match the stored release manifest");
            }
            var existing = lifecycle.findReleaseBundle(release.id());
            if (existing.isPresent()) {
                Bundle stored = validateStored(existing.get());
                if (!stored.digest().equals(bundle.digest())) {
                    throw conflict("RELEASE_BUNDLE_IMMUTABLE",
                            "the release already has a different signed evidence bundle");
                }
                return stored;
            }
            Instant now = clock.now();
            lifecycle.insertReleaseBundle(new ReleaseBundleRow(workspace.value(), release.id(), bundle.canonicalJson(),
                    bundle.digest(), release.manifestDigest(), bundle.signature().keyId(), bundle.signature().signedAt(), now));
            return bundle;
        });
    }

    /** Independent verification: no database, deployment, or signing authority participates. */
    public Verification verify(String json) {
        try { return trust.verify(ReleaseEvidence.parse(json)); }
        catch (RuntimeException invalid) {
            return new Verification(false, "BUNDLE_INVALID", "the portable release bundle is invalid", null);
        }
    }

    public EvidenceAnchor.Receipt anchor(WorkspaceId workspace, UUID releaseId) {
        Bundle bundle = exportBundle(workspace, releaseId);
        EvidenceAnchor.Receipt receipt;
        try {
            receipt = anchor.submit(bundle.digest());
        } catch (UnsupportedOperationException unavailable) {
            throw conflict("ANCHOR_UNAVAILABLE", "no evidence anchor is configured");
        }
        if (!bundle.digest().equals(receipt.bundleDigest()) || !anchor.verify(receipt)) {
            throw conflict("ANCHOR_RECEIPT_INVALID", "the evidence anchor receipt did not verify");
        }
        return receipt;
    }

    /** Called inside the deployment transaction, where TenantContext is already bound. */
    public Bundle trustedBundle(UUID releaseId) {
        ReleaseRow release = lifecycle.findRelease(releaseId).orElseThrow(LifecycleNotFoundException::new);
        return trustedBundle(release);
    }

    /** Returns the exact stored bundle only after row binding, content integrity, and current trust all verify. */
    public Bundle trustedBundle(ReleaseRow release) {
        ReleaseBundleRow stored = lifecycle.findReleaseBundle(release.id())
                .orElseThrow(() -> conflict("SIGNED_EVIDENCE_REQUIRED", "release has no signed evidence bundle"));
        Bundle bundle = validateStored(stored);
        if (!release.id().equals(bundle.payload().releaseId())
                || !release.manifestDigest().equals(bundle.payload().manifestDigest())
                || !release.manifestJson().equals(bundle.payload().manifest())) {
            throw conflict("RELEASE_BUNDLE_MISMATCH", "signed evidence does not bind the admitted release and manifest");
        }
        Verification verified = trust.verify(bundle);
        if (!verified.trusted()) throw conflict(verified.code(), verified.detail());
        return bundle;
    }

    /** Called inside the deployment transaction, where TenantContext is already bound. */
    @Override public void requireAdmissible(ReleaseRow release) {
        trustedBundle(release);
    }

    /** Retained history stays exportable after trust rotation; this validates immutable structure and row bindings. */
    private Bundle validateStored(ReleaseBundleRow stored) {
        Bundle bundle = ReleaseEvidence.parse(stored.bundleJson());
        if (!stored.bundleDigest().equals(bundle.digest())) {
            throw conflict("BUNDLE_INTEGRITY_FAILED", "stored release bundle digest does not verify");
        }
        if (!stored.releaseId().equals(bundle.payload().releaseId())
                || !stored.manifestDigest().equals(bundle.payload().manifestDigest())
                || !stored.signingKeyId().equals(bundle.signature().keyId())
                || !stored.signedAt().equals(bundle.signature().signedAt())) {
            throw conflict("RELEASE_BUNDLE_MISMATCH",
                    "stored release, manifest, signing key, or signing time does not match the signed bundle");
        }
        return bundle;
    }

    private static List<EvidenceReference> evidence(String manifest) {
        var node = com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson.MAPPER.readTree(manifest)
                .path("releaseEvidence");
        if (node.isMissingNode() || node.isNull()) return List.of();
        return com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson.MAPPER.convertValue(node,
                new TypeReference<List<EvidenceReference>>() {});
    }

    private static LifecycleConflictException conflict(String code, String detail) {
        return new LifecycleConflictException(code, detail);
    }
}
