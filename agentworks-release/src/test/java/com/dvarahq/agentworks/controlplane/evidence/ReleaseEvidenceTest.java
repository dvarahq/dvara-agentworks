package com.dvarahq.agentworks.controlplane.evidence;

import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Abom;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.AbomComponent;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Bundle;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Ed25519Signer;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.EvidenceKind;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.EvidenceReference;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Payload;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.TrustPolicy;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseEvidenceTest {
    private static final UUID RELEASE = UUID.fromString("018f0000-0000-7000-8000-000000000054");
    private static final Instant SIGNED_AT = Instant.parse("2026-10-15T12:00:00Z");
    private static final String MANIFEST = "{\"agent\":{\"definitionName\":\"research-agent\",\"specDigest\":\"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},\"manifestVersion\":12,\"models\":{\"reasoning\":{\"fingerprint\":\"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\",\"model\":\"provider/model-v2\"}},\"sandbox\":{\"image\":\"registry.example/runner@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}}";

    @Test
    void equivalentInputsProduceByteIdenticalPayloadAndSortedAbom() {
        var evidenceA = new EvidenceReference(EvidenceKind.EVALUATION, "eval:7", digest("eval"));
        var evidenceB = new EvidenceReference(EvidenceKind.AGENT_TEST, "test:2", digest("test"));
        String evidencedManifest = withEvidence(MANIFEST, List.of(evidenceA, evidenceB));
        Payload first = new Payload(1, RELEASE, evidencedManifest, Digests.prefixed(evidencedManifest),
                List.of(evidenceA, evidenceB), null);
        Payload second = new Payload(1, RELEASE, evidencedManifest, Digests.prefixed(evidencedManifest),
                List.of(evidenceB, evidenceA), Abom.fromManifest(evidencedManifest));

        assertThat(first.canonicalBytes()).containsExactly(second.canonicalBytes());
        assertThat(first.abom().components()).extracting(AbomComponent::path, AbomComponent::identity,
                        AbomComponent::digest)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("r/agent/specDigest", "research-agent",
                                "sha256:" + "a".repeat(64)),
                        org.assertj.core.groups.Tuple.tuple("r/models/reasoning/fingerprint", "provider/model-v2",
                                "sha256:" + "c".repeat(64)),
                        org.assertj.core.groups.Tuple.tuple("r/models/reasoning/metadataHash",
                                "model:reasoning:provider/model-v2",
                                Digests.prefixed("{\"fingerprint\":\"sha256:" + "c".repeat(64)
                                        + "\",\"model\":\"provider/model-v2\"}")),
                        org.assertj.core.groups.Tuple.tuple("r/releaseEvidence/0/digest", "eval:7", digest("eval")),
                        org.assertj.core.groups.Tuple.tuple("r/releaseEvidence/1/digest", "test:2", digest("test")),
                        org.assertj.core.groups.Tuple.tuple("r/sandbox/image", "registry.example/runner",
                                "sha256:" + "b".repeat(64)),
                        org.assertj.core.groups.Tuple.tuple("r/sandbox/metadataHash", "config:sandbox",
                                Digests.prefixed("{\"image\":\"registry.example/runner@sha256:"
                                        + "b".repeat(64) + "\"}")));
    }

    @Test
    void modelWithoutProviderFingerprintStillHasAResolvedIdentityMetadataComponent() {
        String modelEntry = "{\"adapterVersion\":\"1.4.0\",\"alias\":\"coding\","
                + "\"gateway\":\"openai-compatible\",\"gatewayType\":\"openai\","
                + "\"model\":\"gpt-production\",\"protocol\":\"responses\","
                + "\"resolution\":\"RESOLVED\"}";
        String manifest = "{\"manifestVersion\":12,\"models\":{\"reasoning\":" + modelEntry + "}}";

        assertThat(Abom.fromManifest(manifest).components()).singleElement().satisfies(component -> {
            assertThat(component.path()).isEqualTo("r/models/reasoning/metadataHash");
            assertThat(component.identity()).isEqualTo("model:reasoning:gpt-production");
            assertThat(component.digest()).isEqualTo(Digests.prefixed(modelEntry));
        });
    }

    @Test
    void allowlistedResolvedAndConfigurationObjectsReceiveTypedMetadataHashes() {
        String tool = "{\"effect\":{\"action\":\"write\",\"resource\":\"source\",\"risk\":\"HIGH\"},"
                + "\"name\":\"edit\",\"namespace\":\"core\",\"schemaHash\":\"sha256:" + "d".repeat(64)
                + "\",\"version\":\"2.0.0\"}";
        String manifest = "{\"capabilityPacks\":[{\"id\":\"empty\",\"tools\":[],\"version\":\"1.0.0\"},"
                + "{\"id\":\"developer\",\"tools\":[" + tool + "],\"version\":\"2.0.0\"}],"
                + "\"context\":{},\"execution\":{},\"executor\":{\"type\":\"local\"},\"limits\":{},"
                + "\"manifestVersion\":12,\"memory\":{},\"models\":{},\"sandbox\":{},\"skills\":[]}";

        assertThat(Abom.fromManifest(manifest).components()).extracting(AbomComponent::path)
                .contains("r/executor/metadataHash", "r/capabilityPacks/0/metadataHash",
                        "r/capabilityPacks/1/metadataHash", "r/capabilityPacks/1/tools/0/metadataHash",
                        "r/sandbox/metadataHash", "r/limits/metadataHash", "r/execution/metadataHash",
                        "r/context/metadataHash", "r/memory/metadataHash",
                        "r/capabilityPacks/1/tools/0/schemaHash");
        assertThat(Abom.fromManifest(manifest).components())
                .filteredOn(component -> component.path().equals("r/capabilityPacks/1/tools/0/metadataHash"))
                .singleElement().satisfies(component -> {
                    assertThat(component.identity()).isEqualTo("tool:core:edit:2.0.0");
                    assertThat(component.digest()).isEqualTo(Digests.prefixed(tool));
                });
    }

    @Test
    void goldenSignatureRoundTripsAndTamperingFailsIndependently() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] privateBytes = pair.getPrivate().getEncoded().clone();
        var signer = new Ed25519Signer("release-2026", privateBytes, pair.getPublic().getEncoded());
        var trust = new TrustPolicy(Map.of("release-2026", pair.getPublic().getEncoded()), Set.of());
        var simulation = new EvidenceReference(EvidenceKind.SIMULATION, "sim:seed-42", digest("simulation"));
        String evidencedManifest = withEvidence(MANIFEST, List.of(simulation));
        Payload payload = new Payload(1, RELEASE, evidencedManifest, Digests.prefixed(evidencedManifest),
                List.of(simulation), null);
        Bundle signed = signer.sign(payload, SIGNED_AT);

        Bundle portable = ReleaseEvidence.parse(signed.canonicalJson());
        assertThat(trust.verify(portable).trusted()).isTrue();
        assertThat(portable.canonicalJson()).isEqualTo(signed.canonicalJson());

        String tampered = signed.canonicalJson().replace(RELEASE.toString(),
                "018f0000-0000-7000-8000-000000000055");
        assertThat(trust.verify(ReleaseEvidence.parse(tampered))).extracting("trusted", "code")
                .containsExactly(false, "SIGNATURE_INVALID");

        String timestampTampered = signed.canonicalJson().replace(SIGNED_AT.toString(), "2026-10-15T12:00:01Z");
        assertThat(trust.verify(ReleaseEvidence.parse(timestampTampered))).extracting("trusted", "code")
                .containsExactly(false, "SIGNATURE_INVALID");
    }

    @Test
    void fixedRfc8032KeyProducesStableCanonicalPayloadAndSignature() {
        byte[] privateKey = HexFormat.of().parseHex(
                "302e020100300506032b6570042204209d61b19deffd5a60ba844af492ec2cc4"
                + "4449c5697b326919703bac031cae7f60");
        byte[] publicKey = HexFormat.of().parseHex(
                "302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a"
                + "0ee172f3daa62325af021a68f707511a");
        String manifest = "{\"manifestVersion\":12}";
        Payload payload = new Payload(1, RELEASE, manifest, Digests.prefixed(manifest), List.of(), null);
        Bundle bundle = new Ed25519Signer("rfc8032-test-1", privateKey, publicKey).sign(payload, SIGNED_AT);

        assertThat(new String(payload.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
                "{\"bundleVersion\":1,\"releaseId\":\"018f0000-0000-7000-8000-000000000054\","
                + "\"manifest\":\"{\\\"manifestVersion\\\":12}\","
                + "\"manifestDigest\":\"sha256:bcca4dbcfcab93976bfb4da3c130bba1e3659a27a0f3e4fa4fa5875b6caa3804\","
                + "\"evidence\":[],\"abom\":{\"version\":1,\"components\":[],"
                + "\"digest\":\"sha256:db03bb0781b5a77f6723141ef50743934ef3701b0aa56caadef08322f6cd1f41\"}}");
        assertThat(bundle.signature().signature()).isEqualTo(
                "ufO9R7/tfaDWjo9/vGroFMBoJTyydM1TT4VS+j27SBa1DA8AhR5pcwtjwwmqAl+C9hIiR/vBWinoATP2AWmOCQ==");
        assertThat(new TrustPolicy(Map.of("rfc8032-test-1", publicKey), Set.of()).verify(bundle).trusted()).isTrue();
    }

    @Test
    void refusesRevokedUnknownAndMismatchedKeys() throws Exception {
        var trusted = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Payload payload = new Payload(1, RELEASE, MANIFEST, Digests.prefixed(MANIFEST), List.of(), null);
        Bundle bundle = new Ed25519Signer("release-2026", trusted.getPrivate().getEncoded(),
                trusted.getPublic().getEncoded()).sign(payload, SIGNED_AT);

        assertThat(new TrustPolicy(Map.of("release-2026", trusted.getPublic().getEncoded()), Set.of("release-2026"))
                .verify(bundle).code()).isEqualTo("SIGNING_KEY_REVOKED");
        assertThat(new TrustPolicy(Map.of(), Set.of()).verify(bundle).code()).isEqualTo("SIGNING_KEY_UNTRUSTED");
        assertThat(new TrustPolicy(Map.of("release-2026", other.getPublic().getEncoded()), Set.of())
                .verify(bundle).code()).isEqualTo("SIGNING_KEY_MISMATCH");
    }

    @Test
    void signingCanonicalizesMicrosecondsAndPortableNanosecondsAreRejected() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Payload payload = new Payload(1, RELEASE, MANIFEST, Digests.prefixed(MANIFEST), List.of(), null);
        Instant nanos = Instant.parse("2026-10-15T12:00:00.123456789Z");
        Bundle signed = new Ed25519Signer("release-2026", pair.getPrivate().getEncoded(),
                pair.getPublic().getEncoded()).sign(payload, nanos);

        assertThat(signed.signature().signedAt()).isEqualTo(nanos.truncatedTo(ChronoUnit.MICROS));
        assertThat(new TrustPolicy(Map.of("release-2026", pair.getPublic().getEncoded()), Set.of())
                .verify(signed).trusted()).isTrue();
        String nonCanonical = signed.canonicalJson().replace(".123456Z", ".123456789Z");
        assertThatThrownBy(() -> ReleaseEvidence.parse(nonCanonical))
                .hasMessageContaining("canonical microsecond precision");
    }

    @Test
    void rejectsRawMaterialAndInvalidDigestsAtTheBoundary() {
        assertThatThrownBy(() -> new EvidenceReference(EvidenceKind.AGENT_TEST, "secret value", digest("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceReference(EvidenceKind.AGENT_TEST, "test:1", "raw-fixture"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Payload(1, RELEASE, MANIFEST, digest("another-manifest"), List.of(), null))
                .hasMessageContaining("manifest digest");
        Abom incomplete = new Abom(1,
                List.of(new AbomComponent("r/other/digest", "r/other", digest("other"))), null);
        assertThatThrownBy(() -> new Payload(1, RELEASE, MANIFEST, Digests.prefixed(MANIFEST), List.of(), incomplete))
                .hasMessageContaining("every frozen manifest digest");
        assertThatThrownBy(() -> new Payload(1, RELEASE, MANIFEST, Digests.prefixed(MANIFEST),
                List.of(new EvidenceReference(EvidenceKind.EVALUATION, "eval:mismatch", digest("mismatch"))), null))
                .hasMessageContaining("does not match manifest releaseEvidence");
    }

    private static String digest(String value) { return Digests.prefixed(value); }

    private static String withEvidence(String manifest, List<EvidenceReference> evidence) {
        ObjectNode root = (ObjectNode) LifecycleJson.MAPPER.readTree(manifest);
        root.set("releaseEvidence", LifecycleJson.MAPPER.valueToTree(evidence));
        return LifecycleJson.write(root);
    }
}
