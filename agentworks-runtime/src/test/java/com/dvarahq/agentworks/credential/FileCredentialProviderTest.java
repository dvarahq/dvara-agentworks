package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileCredentialProviderTest {
    private static final Instant NOW = Instant.parse("2026-10-08T13:00:00Z");
    private static final CredentialRef REFERENCE = new CredentialRef("file://calendar-token");
    private static final String SECRET_A = "file-secret-a-573c";
    private static final String SECRET_B = "file-secret-b-914e";
    private static final InvocationIdentity IDENTITY = new InvocationIdentity(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private static final Set<PosixFilePermission> DIRECTORY_MODE = Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> SECRET_MODE = Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    @TempDir
    Path temporary;

    @Test
    void rereadsAnAtomicRotationWithoutCaching() throws IOException {
        Path root = root();
        write(root.resolve("calendar"), SECRET_A);
        FileCredentialProvider provider = provider(root, "calendar", CredentialScope.workspace(IDENTITY.workspaceId()));

        assertThat(resolve(provider, provider.authorize(REFERENCE, IDENTITY, NOW))).isEqualTo(SECRET_A);
        Path replacement = write(root.resolve("replacement"), SECRET_B);
        Files.move(replacement, root.resolve("calendar"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        assertThat(resolve(provider, provider.authorize(REFERENCE, IDENTITY, NOW))).isEqualTo(SECRET_B);
        assertThat(provider.toString() + provider.registration()).doesNotContain(SECRET_A, SECRET_B, "calendar");
    }

    @Test
    void permitsKubernetesStyleContainedSymlinkButRejectsEscape() throws IOException {
        Path root = root();
        Path version = Files.createDirectory(root.resolve("..2026_10_08"));
        Files.setPosixFilePermissions(version, DIRECTORY_MODE);
        write(version.resolve("token"), SECRET_A);
        Files.createSymbolicLink(root.resolve("..data"), version.getFileName());
        Files.createSymbolicLink(root.resolve("token"), Path.of("..data", "token"));
        FileCredentialProvider provider = provider(root, "token", CredentialScope.platform());
        assertThat(resolve(provider, provider.authorize(REFERENCE, IDENTITY, NOW))).isEqualTo(SECRET_A);

        Path outside = write(temporary.resolve("outside"), SECRET_B);
        Files.delete(root.resolve("token"));
        Files.createSymbolicLink(root.resolve("token"), outside);
        CredentialAuthorization escaped = provider.authorize(REFERENCE, IDENTITY, NOW);
        assertThatThrownBy(() -> provider.resolve(escaped, NOW)).isInstanceOf(CredentialResolutionException.class);
    }

    @Test
    void rejectsLoosePermissionsWrongOwnerInvalidUtf8OversizeAndScopeMismatch() throws IOException {
        Path root = root();
        Path secret = write(root.resolve("token"), SECRET_A);
        FileCredentialProvider provider = provider(root, "token", CredentialScope.workspace(IDENTITY.workspaceId()));

        Files.setPosixFilePermissions(secret, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OTHERS_READ));
        assertUnavailable(provider);
        Files.setPosixFilePermissions(secret, SECRET_MODE);

        String owner = Files.getOwner(root).getName();
        assertThatThrownBy(() -> new FileCredentialProvider(root, owner + "-other",
                bindings("token", CredentialScope.platform()), Duration.ofSeconds(5), 64))
                .isInstanceOf(CredentialResolutionException.class);

        Files.write(secret, new byte[] {(byte) 0xc3, (byte) 0x28});
        Files.setPosixFilePermissions(secret, SECRET_MODE);
        assertUnavailable(provider);

        Files.writeString(secret, "12345");
        Files.setPosixFilePermissions(secret, SECRET_MODE);
        FileCredentialProvider bounded = new FileCredentialProvider(root, owner, bindings("token",
                CredentialScope.platform()), Duration.ofSeconds(5), 4);
        assertUnavailable(bounded);

        FileCredentialProvider wrongScope = provider(root, "token", CredentialScope.user(UUID.randomUUID()));
        assertThatThrownBy(() -> wrongScope.authorize(REFERENCE, IDENTITY, NOW))
                .isInstanceOf(CredentialResolutionException.class);
    }

    @Test
    void grantsExpireAreSingleUseAndConfigurationRejectsUnsafePaths() throws IOException {
        Path root = root();
        write(root.resolve("token"), SECRET_A);
        FileCredentialProvider provider = provider(root, "token", CredentialScope.platform());
        CredentialAuthorization expired = provider.authorize(REFERENCE, IDENTITY, NOW);
        assertThatThrownBy(() -> provider.resolve(expired, NOW.plusSeconds(5)))
                .isInstanceOf(CredentialResolutionException.class);
        CredentialAuthorization used = provider.authorize(REFERENCE, IDENTITY, NOW);
        assertThat(resolve(provider, used)).isEqualTo(SECRET_A);
        assertThatThrownBy(() -> provider.resolve(used, NOW)).isInstanceOf(CredentialResolutionException.class);

        assertThatThrownBy(() -> new FileCredentialProvider.Binding(Path.of("../token"), CredentialScope.platform()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileCredentialProvider.Binding(root.resolve("token"),
                CredentialScope.platform())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileCredentialProvider(root,
                Map.of(new CredentialRef("env://token"),
                        new FileCredentialProvider.Binding(Path.of("token"), CredentialScope.platform()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Path root() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("secrets-" + UUID.randomUUID()));
        Files.setPosixFilePermissions(root, DIRECTORY_MODE);
        return root;
    }

    private static Path write(Path path, String value) throws IOException {
        Files.writeString(path, value);
        Files.setPosixFilePermissions(path, SECRET_MODE);
        return path;
    }

    private static FileCredentialProvider provider(Path root, String path, CredentialScope scope) {
        return new FileCredentialProvider(root, bindings(path, scope));
    }

    private static Map<CredentialRef, FileCredentialProvider.Binding> bindings(String path, CredentialScope scope) {
        return Map.of(REFERENCE, new FileCredentialProvider.Binding(Path.of(path), scope));
    }

    private static void assertUnavailable(FileCredentialProvider provider) {
        CredentialAuthorization grant = provider.authorize(REFERENCE, IDENTITY, NOW);
        assertThatThrownBy(() -> provider.resolve(grant, NOW)).isInstanceOf(CredentialResolutionException.class);
    }

    private static String resolve(FileCredentialProvider provider, CredentialAuthorization authorization) {
        try (ResolvedCredential credential = provider.resolve(authorization, NOW)) {
            return credential.use(NOW, String::new);
        }
    }
}
