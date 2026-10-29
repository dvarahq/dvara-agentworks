package com.dvarahq.agentworks.controlplane.evidence;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/** Canonical, secret-free release evidence and independent Ed25519 verification contracts (issue #54). */
public final class ReleaseEvidence {
    public static final int BUNDLE_VERSION = 1;
    public static final int ABOM_VERSION = 1;
    public static final int SIGNATURE_INPUT_VERSION = 1;
    private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern EMBEDDED_DIGEST = Pattern.compile("(?:^|@)(sha256:[0-9a-f]{64})$");
    private static final Pattern IDENTITY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,255}$");

    private ReleaseEvidence() {}

    public enum EvidenceKind { AGENT_TEST, SIMULATION, EVALUATION, CONFIGURATION, SECURITY, BUILD }

    /** A content-addressed reference only. Evidence payloads and sensitive fixtures never enter a release. */
    public record EvidenceReference(EvidenceKind kind, String identity, String digest) {
        public EvidenceReference {
            Objects.requireNonNull(kind, "kind");
            identity = ReleaseEvidence.identity(identity, "evidence identity");
            digest = ReleaseEvidence.digest(digest, "evidence digest");
        }
    }

    /** One dependency in the Agent Bill of Materials. */
    public record AbomComponent(String path, String identity, String digest) {
        public AbomComponent {
            path = ReleaseEvidence.identity(path, "ABOM path");
            identity = ReleaseEvidence.identity(identity, "ABOM identity");
            digest = ReleaseEvidence.digest(digest, "ABOM digest");
        }
    }

    public record Abom(int version, List<AbomComponent> components, String digest) {
        public Abom {
            if (version != ABOM_VERSION) throw new IllegalArgumentException("unsupported ABOM version");
            components = components == null ? List.of() : components.stream()
                    .sorted(Comparator.comparing(AbomComponent::path).thenComparing(AbomComponent::identity)).toList();
            String calculated = Digests.prefixed(LifecycleJson.write(Map.of("components", components,
                    "version", version)));
            if (digest == null) digest = calculated;
            if (!calculated.equals(digest)) throw new IllegalArgumentException("ABOM digest does not verify");
        }

        public static Abom fromManifest(String manifest) {
            JsonNode root = LifecycleJson.MAPPER.readTree(manifest);
            List<AbomComponent> components = new ArrayList<>();
            collect(root, "$", components);
            Abom calculated = new Abom(ABOM_VERSION, components, null);
            JsonNode embedded = root.get("abom");
            if (embedded != null && !embedded.isNull()) {
                Abom declared = LifecycleJson.MAPPER.convertValue(embedded, Abom.class);
                if (!calculated.equals(declared)) {
                    throw new IllegalArgumentException("embedded ABOM does not describe the frozen manifest");
                }
            }
            return calculated;
        }

        private static void collect(JsonNode node, String path, List<AbomComponent> target) {
            if (node.isObject()) {
                String metadataIdentity = metadataIdentity(node, path);
                if (metadataIdentity != null) {
                    target.add(new AbomComponent(safePath(path + "/metadataHash"), metadataIdentity,
                            Digests.prefixed(LifecycleJson.write(node))));
                }
                TreeMap<String, JsonNode> fields = new TreeMap<>();
                node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
                fields.forEach((name, child) -> {
                    String childPath = path + "/" + name;
                    if ("$".equals(path) && "abom".equals(name)) {
                        return; // An ABOM never inventories its own digest or component copies.
                    } else if (child.isString() && isDigestName(name) && DIGEST.matcher(child.stringValue()).matches()) {
                        target.add(new AbomComponent(safePath(childPath), dependencyIdentity(node, path),
                                child.stringValue()));
                    } else if (child.isString() && isDependencyLocator(name)) {
                        var embedded = EMBEDDED_DIGEST.matcher(child.stringValue());
                        if (embedded.find()) {
                            String locator = child.stringValue().substring(0, child.stringValue().length()
                                    - embedded.group(1).length()).replaceFirst("@$", "");
                            target.add(new AbomComponent(safePath(childPath),
                                    locator.isBlank() ? dependencyIdentity(node, path) : safeIdentity(locator),
                                    embedded.group(1)));
                        } else {
                            collect(child, childPath, target);
                        }
                    } else {
                        collect(child, childPath, target);
                    }
                });
            } else if (node.isArray()) {
                for (int index = 0; index < node.size(); index++) collect(node.get(index), path + "/" + index, target);
            }
        }

        /** Closed manifest paths whose complete, secret-free resolved metadata is dependency/configuration evidence. */
        private static String metadataIdentity(JsonNode node, String path) {
            if ("$/executor".equals(path)) return identity("executor", text(node, "type", "none"));
            if (path.startsWith("$/models/") && path.indexOf('/', "$/models/".length()) < 0) {
                return identity("model", path.substring("$/models/".length()), text(node, "model", "unknown"));
            }
            if (path.matches("^\\$/capabilityPacks/[0-9]+$")) {
                return identity("capability-pack", text(node, "id", "unknown"), text(node, "version", "unknown"));
            }
            if (path.matches("^\\$/capabilityPacks/[0-9]+/tools/[0-9]+$")) {
                return identity("tool", text(node, "namespace", "unknown"), text(node, "name", "unknown"),
                        text(node, "version", "unknown"));
            }
            if (path.matches("^\\$/skills/[0-9]+$")) return identity("skill", text(node, "id", "unknown"));
            if (Set.of("$/sandbox", "$/limits", "$/execution", "$/context", "$/memory").contains(path)) {
                return identity("config", path.substring(2));
            }
            return null;
        }

        private static String text(JsonNode node, String field, String fallback) {
            JsonNode value = node.get(field);
            return value != null && value.isString() && !value.stringValue().isBlank() ? value.stringValue() : fallback;
        }

        private static String identity(String... parts) {
            return safeIdentity(String.join(":", parts));
        }

        private static boolean isDigestName(String name) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith("digest") || lower.endsWith("hash") || lower.endsWith("fingerprint");
        }

        private static boolean isDependencyLocator(String name) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.equals("image") || lower.equals("artifact") || lower.equals("source")
                    || lower.equals("uri") || lower.equals("locator");
        }

        /** Uses only public dependency metadata fields; arbitrary sibling values (including secrets) are ignored. */
        private static String dependencyIdentity(JsonNode parent, String fallback) {
            for (String field : List.of("identity", "definitionName", "name", "model", "providerModel", "alias",
                    "image", "source", "artifact", "id", "versionId")) {
                JsonNode candidate = parent.get(field);
                if (candidate != null && candidate.isString() && !candidate.stringValue().isBlank()) {
                    String value = candidate.stringValue().replaceFirst("@sha256:[0-9a-f]{64}$", "");
                    return safeIdentity(value);
                }
            }
            return safeIdentity(fallback);
        }

        private static String safePath(String value) {
            String normalized = value.replace("$", "r").replaceAll("[^A-Za-z0-9._:/@+-]", "_");
            return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
        }
        private static String safeIdentity(String value) {
            String normalized = value.replace('$', 'r').replaceAll("[^A-Za-z0-9._:/@+-]", "_");
            return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
        }
    }

    /** Signature-free bytes. These exact UTF-8 bytes are signed and independently reproducible. */
    public record Payload(int bundleVersion, UUID releaseId, String manifest, String manifestDigest,
            List<EvidenceReference> evidence, Abom abom) {
        public Payload {
            if (bundleVersion != BUNDLE_VERSION) throw new IllegalArgumentException("unsupported bundle version");
            Objects.requireNonNull(releaseId, "releaseId");
            Objects.requireNonNull(manifest, "manifest");
            manifestDigest = digest(manifestDigest, "manifest digest");
            if (!Digests.prefixed(manifest).equals(manifestDigest)) {
                throw new IllegalArgumentException("manifest digest does not verify");
            }
            evidence = evidence == null ? List.of() : evidence.stream()
                    .sorted(Comparator.comparing((EvidenceReference value) -> value.kind().name())
                            .thenComparing(EvidenceReference::identity).thenComparing(EvidenceReference::digest)).toList();
            JsonNode declaredEvidence = LifecycleJson.MAPPER.readTree(manifest).path("releaseEvidence");
            List<EvidenceReference> manifestEvidence = new ArrayList<>();
            if (!declaredEvidence.isMissingNode() && !declaredEvidence.isNull()) {
                if (!declaredEvidence.isArray()) {
                    throw new IllegalArgumentException("manifest releaseEvidence must be an array");
                }
                for (JsonNode item : declaredEvidence.values()) {
                    manifestEvidence.add(LifecycleJson.read(item.toString(), EvidenceReference.class));
                }
            }
            manifestEvidence = manifestEvidence.stream()
                    .sorted(Comparator.comparing((EvidenceReference value) -> value.kind().name())
                            .thenComparing(EvidenceReference::identity).thenComparing(EvidenceReference::digest)).toList();
            if (!manifestEvidence.equals(evidence)) {
                throw new IllegalArgumentException("payload evidence does not match manifest releaseEvidence");
            }
            Abom expectedAbom = Abom.fromManifest(manifest);
            if (abom == null) {
                abom = expectedAbom;
            } else if (!expectedAbom.equals(abom)) {
                throw new IllegalArgumentException("ABOM does not describe every frozen manifest digest");
            }
        }

        public byte[] canonicalBytes() { return LifecycleJson.write(this).getBytes(StandardCharsets.UTF_8); }
    }

    public record SignatureEnvelope(String algorithm, String keyId, String publicKeyFingerprint,
            String signature, Instant signedAt) {
        public SignatureEnvelope {
            if (!"Ed25519".equals(algorithm)) throw new IllegalArgumentException("unsupported signature algorithm");
            keyId = identity(keyId, "key id");
            publicKeyFingerprint = digest(publicKeyFingerprint, "public-key fingerprint");
            if (signature == null || signature.isBlank() || signature.length() > 256) {
                throw new IllegalArgumentException("signature is required");
            }
            Objects.requireNonNull(signedAt, "signedAt");
            if (!signedAt.equals(signedAt.truncatedTo(ChronoUnit.MICROS))) {
                throw new IllegalArgumentException("signedAt must use canonical microsecond precision");
            }
        }
    }

    /** Versioned canonical signature input. Envelope metadata that affects provenance is signed, not merely carried. */
    private record SignatureInput(int version, Payload payload, String algorithm, String keyId,
            String publicKeyFingerprint, Instant signedAt) {}

    public record Bundle(Payload payload, SignatureEnvelope signature) {
        public Bundle { Objects.requireNonNull(payload, "payload"); Objects.requireNonNull(signature, "signature"); }
        public String canonicalJson() { return LifecycleJson.write(this); }
        public String digest() { return Digests.prefixed(canonicalJson()); }
    }

    public record Verification(boolean trusted, String code, String detail, String bundleDigest) {
        static Verification trusted(Bundle bundle) {
            return new Verification(true, "TRUSTED", "signature and all content digests verified", bundle.digest());
        }
        static Verification refused(String code, String detail) { return new Verification(false, code, detail, null); }
    }

    /** Control-plane signer. Private key material is accepted only here and is never exposed by this API. */
    public static final class Ed25519Signer {
        private final String keyId;
        private final PrivateKey privateKey;
        private final PublicKey publicKey;

        public Ed25519Signer(String keyId, byte[] pkcs8PrivateKey, byte[] x509PublicKey) {
            this.keyId = identity(keyId, "key id");
            try {
                KeyFactory factory = KeyFactory.getInstance("Ed25519");
                this.privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8PrivateKey.clone()));
                this.publicKey = factory.generatePublic(new X509EncodedKeySpec(x509PublicKey.clone()));
            } catch (GeneralSecurityException failure) {
                throw new IllegalArgumentException("invalid Ed25519 signing key", failure);
            } finally {
                java.util.Arrays.fill(pkcs8PrivateKey, (byte) 0);
            }
        }

        public Bundle sign(Payload payload, Instant now) {
            try {
                Instant signedAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
                String algorithm = "Ed25519";
                String publicKeyFingerprint = fingerprint(publicKey);
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(privateKey);
                signer.update(signatureBytes(payload, algorithm, keyId, publicKeyFingerprint, signedAt));
                String value = Base64.getEncoder().encodeToString(signer.sign());
                return new Bundle(payload, new SignatureEnvelope(algorithm, keyId, publicKeyFingerprint, value,
                        signedAt));
            } catch (GeneralSecurityException failure) {
                throw new IllegalStateException("release signing failed", failure);
            }
        }
    }

    /** One configured trust set plus a revocation list, the ADR-030 OSS 1.0 minimum. */
    public static final class TrustPolicy {
        private final Map<String, PublicKey> keys;
        private final Set<String> revoked;

        public TrustPolicy(Map<String, byte[]> x509Keys, Collection<String> revokedKeyIds) {
            try {
                KeyFactory factory = KeyFactory.getInstance("Ed25519");
                Map<String, PublicKey> parsed = new TreeMap<>();
                x509Keys.forEach((id, value) -> {
                    try { parsed.put(identity(id, "key id"), factory.generatePublic(new X509EncodedKeySpec(value))); }
                    catch (GeneralSecurityException failure) { throw new IllegalArgumentException("invalid trusted Ed25519 key " + id, failure); }
                });
                this.keys = Map.copyOf(parsed);
                this.revoked = Set.copyOf(revokedKeyIds == null ? Set.of() : revokedKeyIds);
            } catch (GeneralSecurityException failure) {
                throw new IllegalStateException("Ed25519 is unavailable", failure);
            }
        }

        public Verification verify(Bundle bundle) {
            try {
                // Constructors repeat every content-integrity check after deserialization.
                Payload payload = new Payload(bundle.payload().bundleVersion(), bundle.payload().releaseId(),
                        bundle.payload().manifest(), bundle.payload().manifestDigest(), bundle.payload().evidence(),
                        bundle.payload().abom());
                SignatureEnvelope envelope = bundle.signature();
                if (revoked.contains(envelope.keyId())) return Verification.refused("SIGNING_KEY_REVOKED", "the signing key is revoked");
                PublicKey key = keys.get(envelope.keyId());
                if (key == null) return Verification.refused("SIGNING_KEY_UNTRUSTED", "the signing key is not trusted");
                if (!fingerprint(key).equals(envelope.publicKeyFingerprint())) {
                    return Verification.refused("SIGNING_KEY_MISMATCH", "the public-key fingerprint does not match trust metadata");
                }
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key);
                verifier.update(signatureBytes(payload, envelope.algorithm(), envelope.keyId(),
                        envelope.publicKeyFingerprint(), envelope.signedAt()));
                if (!verifier.verify(Base64.getDecoder().decode(envelope.signature()))) {
                    return Verification.refused("SIGNATURE_INVALID", "the release payload was tampered or signed by another key");
                }
                return Verification.trusted(bundle);
            } catch (IllegalArgumentException failure) {
                return Verification.refused("BUNDLE_INVALID", failure.getMessage());
            } catch (GeneralSecurityException failure) {
                return Verification.refused("SIGNATURE_INVALID", "signature verification failed");
            }
        }
    }

    public static Bundle parse(String json) {
        if (json == null || json.length() > 4_000_000) throw new IllegalArgumentException("release bundle is missing or too large");
        Bundle parsed = LifecycleJson.read(json, Bundle.class);
        return new Bundle(new Payload(parsed.payload().bundleVersion(), parsed.payload().releaseId(),
                parsed.payload().manifest(), parsed.payload().manifestDigest(), parsed.payload().evidence(),
                parsed.payload().abom()), parsed.signature());
    }

    private static String fingerprint(PublicKey key) { return Digests.prefixed(Base64.getEncoder().encodeToString(key.getEncoded())); }
    private static byte[] signatureBytes(Payload payload, String algorithm, String keyId,
            String publicKeyFingerprint, Instant signedAt) {
        return LifecycleJson.write(new SignatureInput(SIGNATURE_INPUT_VERSION, payload, algorithm, keyId,
                publicKeyFingerprint, signedAt)).getBytes(StandardCharsets.UTF_8);
    }
    private static String digest(String value, String name) {
        if (value == null || !DIGEST.matcher(value).matches()) throw new IllegalArgumentException(name + " must be sha256:<64 lowercase hex>");
        return value;
    }
    private static String identity(String value, String name) {
        if (value == null || !IDENTITY.matcher(value).matches()) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
