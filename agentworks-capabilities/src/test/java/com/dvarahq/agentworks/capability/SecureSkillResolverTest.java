package com.dvarahq.agentworks.capability;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureSkillResolverTest {
    private static final byte[] MARKDOWN = "# Safe skill\nUse only cited sources.".getBytes(StandardCharsets.UTF_8);
    private static final Map<String, byte[]> ENTRIES = Map.of("SKILL.md", MARKDOWN,
            "examples/example.md", "example".getBytes(StandardCharsets.UTF_8));
    private static final String DIGEST = SecureSkillResolver.digest(ENTRIES);
    private static final String COMMIT = "a".repeat(40);
    private static final String OCI_DIGEST = "sha256:" + "b".repeat(64);

    @Test
    void resolvesLocalGitHttpAndOciToTheSameVerifiedEvidence() throws Exception {
        for (SkillSourceKind kind : SkillSourceKind.values()) {
            SkillSource source = source(kind);
            SkillFetch fetched = fetch(source, List.of(InetAddress.getByName("8.8.8.8")));
            SecureSkillResolver resolver = new SecureSkillResolver(Map.of(kind, ignored -> fetched));
            ResolvedSkill resolved = resolver.resolve(source);
            assertThat(resolved.contentDigest()).isEqualTo(DIGEST);
            assertThat(resolved.resolvedRef()).isEqualTo(source.versionRef());
            assertThat(new String(resolved.skillMarkdown(), StandardCharsets.UTF_8)).startsWith("# Safe skill");
            assertThat(resolved.license()).isEqualTo("Apache-2.0");
        }
    }

    @Test
    void rejectsDriftPrivateDnsRedirectsMutableRefsAndMaliciousArchives() throws Exception {
        SkillSource http = source(SkillSourceKind.HTTP);
        List<InetAddress> publicAddress = List.of(InetAddress.getByName("8.8.8.8"));
        SkillFetch privateFetch = fetch(http, List.of(InetAddress.getByName("127.0.0.1")));
        assertThatThrownBy(() -> new SecureSkillResolver(Map.of(SkillSourceKind.HTTP, ignored -> privateFetch))
                .resolve(http)).hasMessageContaining("private address");

        SkillFetch redirect = new SkillFetch(http.versionRef(), List.of(URI.create("https://evil.example/SKILL.md")),
                List.of(InetAddress.getByName("8.8.8.8")), pack(ENTRIES), null, "Apache-2.0", "commit:test");
        assertThatThrownBy(() -> new SecureSkillResolver(Map.of(SkillSourceKind.HTTP, ignored -> redirect))
                .resolve(http)).hasMessageContaining("fixed origin");

        SkillSource drift = new SkillSource("safe", SkillSourceKind.HTTP, http.uri(), "etag-1",
                "sha256:" + "0".repeat(64), null, "default@1", "1", SkillLifecycle.ACTIVE,
                SkillLoadingPolicy.ON_DEMAND, Set.of());
        assertThatThrownBy(() -> new SecureSkillResolver(Map.of(SkillSourceKind.HTTP,
                ignored -> fetch(drift, publicAddress))).resolve(drift))
                .hasMessageContaining("digest drifted");

        SkillSource mutableGit = new SkillSource("safe", SkillSourceKind.GIT,
                URI.create("git+https://skills.example/repo.git"), "main", DIGEST, null, "default@1", "1",
                SkillLifecycle.ACTIVE, SkillLoadingPolicy.ON_DEMAND, Set.of());
        assertThatThrownBy(() -> new SecureSkillResolver(Map.of(SkillSourceKind.GIT,
                ignored -> fetch(mutableGit, publicAddress))).resolve(mutableGit))
                .hasMessageContaining("full commit");

        SkillFetch traversal = new SkillFetch(http.versionRef(), List.of(http.uri()),
                List.of(InetAddress.getByName("8.8.8.8")), new SkillPackage(List.of(
                        new SkillPackage.Entry("SKILL.md", MARKDOWN, false),
                        new SkillPackage.Entry("../secret", new byte[]{1}, false))), null, "Apache-2.0", "test");
        assertThatThrownBy(() -> new SecureSkillResolver(Map.of(SkillSourceKind.HTTP, ignored -> traversal))
                .resolve(http)).hasMessageContaining("traversal");

        SkillFetch redirectChain = new SkillFetch(http.versionRef(), List.of(http.uri(), http.uri(), http.uri(),
                http.uri(), http.uri()), publicAddress, pack(ENTRIES), null, "Apache-2.0", "test");
        assertThatThrownBy(() -> new SecureSkillResolver(Map.of(SkillSourceKind.HTTP, ignored -> redirectChain))
                .resolve(http)).hasMessageContaining("network evidence");

        SkillSource signed = new SkillSource("safe", SkillSourceKind.HTTP, http.uri(), http.versionRef(), DIGEST,
                "sha256:" + "c".repeat(64), "signed@1", "1", SkillLifecycle.ACTIVE,
                SkillLoadingPolicy.ON_DEMAND, Set.of());
        assertThatThrownBy(() -> new SecureSkillResolver(Map.of(SkillSourceKind.HTTP,
                ignored -> fetch(signed, publicAddress))).resolve(signed)).hasMessageContaining("signature");

        SkillFetch archiveBomb = new SkillFetch(http.versionRef(), List.of(http.uri()), publicAddress,
                new SkillPackage(List.of(new SkillPackage.Entry("SKILL.md", new byte[1_048_577], false))),
                null, "Apache-2.0", "test");
        assertThatThrownBy(() -> new SecureSkillResolver(Map.of(SkillSourceKind.HTTP, ignored -> archiveBomb))
                .resolve(http)).hasMessageContaining("too large");

        SkillSource revoked = new SkillSource("safe", SkillSourceKind.HTTP, http.uri(), http.versionRef(), DIGEST,
                null, "default@1", "1", SkillLifecycle.ARCHIVED, SkillLoadingPolicy.ON_DEMAND, Set.of());
        assertThatThrownBy(() -> new SecureSkillResolver(Map.of(SkillSourceKind.HTTP,
                ignored -> fetch(revoked, publicAddress))).resolve(revoked)).hasMessageContaining("not active");
    }

    @Test
    void progressiveLoaderHonorsPolicyTriggersAndBudget() {
        ResolvedSkill always = resolved("always", SkillLoadingPolicy.ALWAYS, Set.of(), "always");
        ResolvedSkill demand = resolved("demand", SkillLoadingPolicy.ON_DEMAND, Set.of(), "demand");
        ResolvedSkill trigger = resolved("trigger", SkillLoadingPolicy.CONTEXT_TRIGGERED, Set.of("java"), "trigger");
        var loaded = new SkillContextLoader().load(List.of(trigger, demand, always), Set.of("demand"), Set.of("java"), 100);
        assertThat(loaded).extracting(SkillContextLoader.LoadedSkill::id).containsExactly("always", "demand", "trigger");
        assertThat(new SkillContextLoader().load(List.of(demand), Set.of(), Set.of(), 100)).isEmpty();
        assertThat(new SkillContextLoader().load(List.of(always), Set.of(), Set.of(), 1)).isEmpty();
    }

    private static SkillSource source(SkillSourceKind kind) {
        URI uri = switch (kind) {
            case LOCAL -> URI.create("file:///opt/skills/safe");
            case GIT -> URI.create("git+https://skills.example/repo.git");
            case HTTP -> URI.create("https://skills.example/safe.tar");
            case OCI -> URI.create("oci://skills.example/safe");
        };
        String ref = switch (kind) { case GIT -> COMMIT; case OCI -> OCI_DIGEST; default -> "release-1"; };
        return new SkillSource("safe", kind, uri, ref, DIGEST, null, "default@1", "1", SkillLifecycle.ACTIVE,
                SkillLoadingPolicy.CONTEXT_TRIGGERED, Set.of("safe-task"));
    }

    private static SkillFetch fetch(SkillSource source, List<InetAddress> addresses) {
        List<URI> origins = source.kind() == SkillSourceKind.LOCAL ? List.of() : List.of(URI.create(
                "https://" + source.uri().getHost() + "/content"));
        return new SkillFetch(source.versionRef(), origins, source.kind() == SkillSourceKind.LOCAL ? List.of() : addresses,
                pack(ENTRIES), null, "Apache-2.0", "build:test");
    }

    private static SkillPackage pack(Map<String, byte[]> entries) {
        return new SkillPackage(entries.entrySet().stream()
                .map(entry -> new SkillPackage.Entry(entry.getKey(), entry.getValue(), false)).toList());
    }

    private static ResolvedSkill resolved(String id, SkillLoadingPolicy policy, Set<String> triggers, String content) {
        Map<String, byte[]> entries = Map.of("SKILL.md", content.getBytes(StandardCharsets.UTF_8));
        return new ResolvedSkill(id, SkillSourceKind.LOCAL, URI.create("file:///skills/" + id), "1", "1",
                SecureSkillResolver.digest(entries), null, "default@1", "1", SkillLifecycle.ACTIVE, policy, triggers,
                "Apache-2.0", "test", entries);
    }
}
