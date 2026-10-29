package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** JIT provider for bounded UTF-8 secrets mounted beneath one explicitly trusted filesystem root. */
public final class FileCredentialProvider implements CredentialProvider {
    public static final String SCHEME = "file";
    public static final Duration DEFAULT_GRANT_TTL = Duration.ofSeconds(5);
    public static final int DEFAULT_MAX_BYTES = 64 * 1024;
    private static final Set<PosixFilePermission> FILE_FORBIDDEN = EnumSet.of(PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
    private static final Set<PosixFilePermission> ROOT_FORBIDDEN = EnumSet.of(PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE);

    /** Non-secret metadata for one exact reference. Paths are relative to the provider's trusted root. */
    public record Binding(Path relativePath, CredentialScope scope) {
        public Binding {
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(scope, "scope");
            if (relativePath.isAbsolute() || relativePath.getNameCount() == 0
                    || relativePath.normalize().startsWith("..") || !relativePath.equals(relativePath.normalize())) {
                throw new IllegalArgumentException("secret path must be a normalized relative path beneath the root");
            }
        }

        @Override
        public String toString() {
            return "Binding[path=<redacted>, scope=" + scope.level() + "]";
        }
    }

    private final Path root;
    private final String expectedOwner;
    private final Map<CredentialRef, Binding> bindings;
    private final Duration grantTtl;
    private final int maxBytes;
    private final Map<UUID, Grant> grants = new LinkedHashMap<>();

    public FileCredentialProvider(Path root, Map<CredentialRef, Binding> bindings) {
        this(root, ownerOf(realRoot(root)), bindings, DEFAULT_GRANT_TTL, DEFAULT_MAX_BYTES);
    }

    public FileCredentialProvider(Path root, String expectedOwner, Map<CredentialRef, Binding> bindings,
            Duration grantTtl, int maxBytes) {
        this.root = realRoot(root);
        this.expectedOwner = requireText(expectedOwner, "expected owner");
        requireSecure(this.root, this.expectedOwner, true);
        Objects.requireNonNull(bindings, "bindings");
        Map<CredentialRef, Binding> snapshot = new LinkedHashMap<>();
        bindings.forEach((reference, binding) -> {
            requireFileReference(reference);
            snapshot.put(reference, Objects.requireNonNull(binding, "binding"));
        });
        if (snapshot.isEmpty()) throw new IllegalArgumentException("at least one file credential is required");
        this.bindings = Map.copyOf(snapshot);
        this.grantTtl = Objects.requireNonNull(grantTtl, "grantTtl");
        if (grantTtl.isZero() || grantTtl.isNegative()) {
            throw new IllegalArgumentException("file credential grant TTL must be positive");
        }
        if (maxBytes < 1) throw new IllegalArgumentException("file credential maximum size must be positive");
        this.maxBytes = maxBytes;
    }

    public CredentialProviderRegistration registration() {
        return new CredentialProviderRegistration(SCHEME, this);
    }

    @Override
    public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity, Instant now) {
        return authorize(CredentialUseRequest.withoutAgent(reference, identity), now);
    }

    @Override
    public synchronized CredentialAuthorization authorize(CredentialUseRequest request, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        Binding binding = bindings.get(request.credentialRef());
        if (binding == null || !binding.scope().permits(request)) {
            throw new CredentialResolutionException("credential reference is unavailable for this scope");
        }
        grants.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        UUID grantId = UUID.randomUUID();
        grants.put(grantId, new Grant(request, binding, now.plus(grantTtl)));
        return new CredentialAuthorization(grantId, request.credentialRef(), request.identity());
    }

    @Override
    public synchronized ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(now, "now");
        Grant grant = grants.remove(authorization.grantId());
        if (grant == null || !grant.request().credentialRef().equals(authorization.credentialRef())
                || !grant.request().identity().equals(authorization.identity())) {
            throw new CredentialResolutionException("authorization grant is invalid or already used");
        }
        if (!now.isBefore(grant.expiresAt())) {
            throw new CredentialResolutionException("authorization grant is expired");
        }
        char[] material = read(grant.binding());
        try {
            return new ResolvedCredential(material, grant.expiresAt());
        } finally {
            Arrays.fill(material, '\0');
        }
    }

    @Override
    public String toString() {
        return "FileCredentialProvider[bindings=" + bindings.size() + ", path=<redacted>, material=<redacted>]";
    }

    private char[] read(Binding binding) {
        byte[] bytes = null;
        try {
            requireSecure(root, expectedOwner, true);
            Path target = root.resolve(binding.relativePath()).normalize().toRealPath();
            if (!target.startsWith(root)) throw unavailable();
            requireSecure(target, expectedOwner, false);
            BasicFileAttributes before = Files.readAttributes(target, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) throw unavailable();
            try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(maxBytes + 1);
            }
            BasicFileAttributes after = Files.readAttributes(target, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            requireSecure(target, expectedOwner, false);
            if (bytes.length == 0 || bytes.length > maxBytes || !Objects.equals(before.fileKey(), after.fileKey())
                    || before.size() != after.size() || before.lastModifiedTime().compareTo(after.lastModifiedTime()) != 0) {
                throw unavailable();
            }
            return decode(bytes);
        } catch (IOException | UnsupportedOperationException failure) {
            throw unavailable();
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private static char[] decode(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            char[] result = new char[decoded.remaining()];
            decoded.get(result);
            if (result.length == 0) throw unavailable();
            return result;
        } catch (CharacterCodingException invalid) {
            throw unavailable();
        }
    }

    private static Path realRoot(Path root) {
        try {
            Path configured = Objects.requireNonNull(root, "root");
            if (Files.isSymbolicLink(configured)) throw unavailable();
            Path real = configured.toRealPath();
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            return real;
        } catch (IOException failure) {
            throw new IllegalArgumentException("file credential root is unavailable");
        }
    }

    private static void requireSecure(Path path, String owner, boolean directory) {
        try {
            if (!owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName())) throw unavailable();
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            Set<PosixFilePermission> forbidden = directory ? ROOT_FORBIDDEN : FILE_FORBIDDEN;
            if (permissions.stream().anyMatch(forbidden::contains)
                    || directory && !permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
                throw unavailable();
            }
        } catch (IOException | UnsupportedOperationException failure) {
            throw unavailable();
        }
    }

    private static String ownerOf(Path path) {
        try {
            return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
        } catch (IOException failure) {
            throw new IllegalArgumentException("file credential root owner is unavailable");
        }
    }

    private static void requireFileReference(CredentialRef reference) {
        Objects.requireNonNull(reference, "reference");
        if (!reference.id().startsWith(SCHEME + ":") || reference.id().length() == SCHEME.length() + 1) {
            throw new IllegalArgumentException("file credential reference must use the file scheme");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value;
    }

    private static CredentialResolutionException unavailable() {
        return new CredentialResolutionException("file credential is unavailable");
    }

    private record Grant(CredentialUseRequest request, Binding binding, Instant expiresAt) { }
}
