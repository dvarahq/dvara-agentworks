package com.dvarahq.agentworks.packaging;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Shell-free, operator-invoked reader for the one-time installation token in the distroless image. */
public final class BootstrapTokenPrinter {
    private static final String TOKEN_FILE_ENV = "AGENTWORKS_SECURITY_BOOTSTRAP_TOKEN_FILE";
    private static final Path DEFAULT_TOKEN_FILE = Path.of("/var/lib/agentworks/bootstrap-token");
    private static final int MAX_TOKEN_BYTES = 4096;

    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new IllegalArgumentException("BootstrapTokenPrinter accepts no arguments");
        String configured = System.getenv(TOKEN_FILE_ENV);
        Path tokenFile = configured == null || configured.isBlank() ? DEFAULT_TOKEN_FILE : Path.of(configured);
        print(tokenFile, System.out);
    }

    static void print(Path tokenFile, PrintStream output) throws IOException {
        if (Files.isSymbolicLink(tokenFile)
                || !Files.isRegularFile(tokenFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("bootstrap token is not present in this Pod");
        }
        long size = Files.size(tokenFile);
        if (size <= 0 || size > MAX_TOKEN_BYTES) throw new IOException("bootstrap token file has an invalid size");
        String token = Files.readString(tokenFile).strip();
        if (token.isEmpty()) throw new IOException("bootstrap token file is empty");
        output.println(token);
    }

    private BootstrapTokenPrinter() {}
}
