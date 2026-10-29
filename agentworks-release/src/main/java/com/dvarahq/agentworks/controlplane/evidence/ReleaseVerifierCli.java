package com.dvarahq.agentworks.controlplane.evidence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Database-free verifier: {@code <bundle.json> <key-id> <x509-public-key.der> [revoked-key-id...]}. */
public final class ReleaseVerifierCli {
    private ReleaseVerifierCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: ReleaseVerifierCli <bundle.json> <key-id> <x509-public-key.der> [revoked-key-id...]");
            System.exit(2);
        }
        String bundle = Files.readString(Path.of(args[0]));
        var policy = new ReleaseEvidence.TrustPolicy(Map.of(args[1], Files.readAllBytes(Path.of(args[2]))),
                args.length == 3 ? Set.of() : Set.of(java.util.Arrays.copyOfRange(args, 3, args.length)));
        ReleaseEvidence.Verification result;
        try {
            result = policy.verify(ReleaseEvidence.parse(bundle));
        } catch (RuntimeException invalid) {
            result = new ReleaseEvidence.Verification(false, "BUNDLE_INVALID",
                    "the portable release bundle is invalid", null);
        }
        System.out.println(com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson.write(result));
        if (!result.trusted()) System.exit(1);
    }
}
