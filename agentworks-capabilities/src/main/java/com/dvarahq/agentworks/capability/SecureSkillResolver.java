package com.dvarahq.agentworks.capability;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Fail-closed resolver for LOCAL/GIT/HTTP/OCI packages and their supply-chain/network evidence. */
public final class SecureSkillResolver {
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final long MAX_ENTRY_BYTES = 1_048_576;
    private static final long MAX_PACKAGE_BYTES = 2_097_152;
    private static final int MAX_ENTRIES = 128;
    private final Map<SkillSourceKind, SkillTransport> transports;

    public SecureSkillResolver(Map<SkillSourceKind, SkillTransport> transports) {
        this.transports = Map.copyOf(Objects.requireNonNull(transports, "transports"));
    }

    public ResolvedSkill resolve(SkillSource source) {
        Objects.requireNonNull(source, "source");
        if (source.lifecycle() != SkillLifecycle.ACTIVE) throw new IllegalArgumentException("skill is not active");
        validateSource(source);
        SkillTransport transport = transports.get(source.kind());
        if (transport == null) throw new IllegalArgumentException("skill source transport is unavailable");
        SkillFetch fetch = Objects.requireNonNull(transport.fetch(source), "skill fetch");
        validateNetwork(source, fetch);
        if (!source.versionRef().equals(fetch.resolvedRef())) {
            throw new IllegalArgumentException("skill source drifted from its immutable reference");
        }
        if (source.signatureDigest() != null && !source.signatureDigest().equals(fetch.signatureDigest())) {
            throw new IllegalArgumentException("skill signature verification failed");
        }
        if (fetch.license() == null || fetch.license().isBlank() || fetch.provenance() == null
                || fetch.provenance().isBlank()) throw new IllegalArgumentException("skill provenance is incomplete");

        Map<String, byte[]> entries = validatePackage(fetch.skillPackage());
        String digest = digest(entries);
        if (!source.expectedDigest().equals(digest)) throw new IllegalArgumentException("skill content digest drifted");
        return new ResolvedSkill(source.id(), source.kind(), source.uri(), source.versionRef(), fetch.resolvedRef(),
                digest, fetch.signatureDigest(), source.trustPolicy(), source.manifestVersion(), source.lifecycle(),
                source.loadingPolicy(), source.contextTriggers(), fetch.license(), fetch.provenance(), entries);
    }

    private static void validateSource(SkillSource source) {
        URI uri = source.uri();
        if (uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException("unsafe skill URI");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        switch (source.kind()) {
            case LOCAL -> { if (!"file".equals(scheme)) throw new IllegalArgumentException("LOCAL skills require file URI"); }
            case HTTP -> { if (!"https".equals(scheme)) throw new IllegalArgumentException("HTTP skills require HTTPS"); }
            case GIT -> {
                if (!"git+https".equals(scheme) || !COMMIT.matcher(source.versionRef()).matches()) {
                    throw new IllegalArgumentException("GIT skills require git+https and a full commit");
                }
            }
            case OCI -> {
                if (!"oci".equals(scheme) || !DIGEST.matcher(source.versionRef()).matches()) {
                    throw new IllegalArgumentException("OCI skills require an immutable sha256 digest");
                }
            }
        }
        if (source.kind() != SkillSourceKind.LOCAL && (uri.getHost() == null || blockedHost(uri.getHost()))) {
            throw new IllegalArgumentException("skill source host is not permitted");
        }
    }

    private static void validateNetwork(SkillSource source, SkillFetch fetch) {
        if (source.kind() == SkillSourceKind.LOCAL) {
            if (!fetch.origins().isEmpty() || !fetch.resolvedAddresses().isEmpty()) {
                throw new IllegalArgumentException("LOCAL skill returned network evidence");
            }
            return;
        }
        if (fetch.origins().isEmpty() || fetch.origins().size() > 4 || fetch.resolvedAddresses().isEmpty()) {
            throw new IllegalArgumentException("remote skill network evidence is incomplete");
        }
        String expectedHost = source.uri().getHost().toLowerCase(Locale.ROOT);
        for (URI origin : fetch.origins()) {
            if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null
                    || !expectedHost.equals(origin.getHost().toLowerCase(Locale.ROOT))
                    || origin.getUserInfo() != null || origin.getFragment() != null) {
                throw new IllegalArgumentException("skill redirect escaped its fixed origin");
            }
        }
        for (InetAddress address : fetch.resolvedAddresses()) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("skill source resolved to a private address");
            }
        }
    }

    private static Map<String, byte[]> validatePackage(SkillPackage skillPackage) {
        if (skillPackage == null || skillPackage.entries().isEmpty() || skillPackage.entries().size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("skill package entry count is out of range");
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        for (SkillPackage.Entry entry : skillPackage.entries()) {
            String path = entry.path().replace('\\', '/');
            if (entry.symbolicLink() || path.startsWith("/") || path.isBlank() || path.contains("//")) {
                throw new IllegalArgumentException("unsafe skill package entry");
            }
            for (String part : path.split("/")) {
                if (part.isBlank() || part.equals(".") || part.equals("..")) {
                    throw new IllegalArgumentException("skill package path traversal");
                }
            }
            byte[] bytes = entry.content();
            if (bytes.length > MAX_ENTRY_BYTES || MAX_PACKAGE_BYTES - total < bytes.length) {
                throw new IllegalArgumentException("skill package is too large");
            }
            total += bytes.length;
            if (entries.put(path, bytes) != null) throw new IllegalArgumentException("duplicate skill package path");
        }
        if (!entries.containsKey("SKILL.md") || entries.get("SKILL.md").length == 0) {
            throw new IllegalArgumentException("skill package has no SKILL.md");
        }
        return Map.copyOf(entries);
    }

    static String digest(Map<String, byte[]> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            entries.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey)).forEach(entry -> {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue());
                digest.update((byte) 0);
            });
            return "sha256:" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Computes the canonical package digest for registry/import adapters and deterministic fixtures. */
    public static String canonicalDigest(Map<String, byte[]> entries) {
        return digest(validatePackage(new SkillPackage(entries.entrySet().stream()
                .map(entry -> new SkillPackage.Entry(entry.getKey(), entry.getValue(), false)).toList())));
    }

    private static boolean blockedHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("localhost") || value.endsWith(".localhost") || value.equals("0.0.0.0")
                || value.equals("::1") || value.startsWith("127.") || value.startsWith("10.")
                || value.startsWith("192.168.") || value.startsWith("169.254.");
    }
}
