package com.dvarahq.agentworks.domain.sandbox;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Complete immutable sandbox configuration captured by a release manifest. */
public record SandboxConfiguration(String provider, String provisioning, Optional<String> image, BigDecimal cpu,
        String memory, int pidLimit, String networkPolicy, String maxRuntime) {
    private static final Pattern DIGEST_IMAGE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/:+-]*@sha256:[a-f0-9]{64}$");
    private static final Pattern MEMORY = Pattern.compile("^[1-9][0-9]*(Ki|Mi|Gi|Ti)?$");
    private static final Pattern RUNTIME = Pattern.compile("^[1-9][0-9]*(ms|s|m|h)$");
    private static final Map<String, BigInteger> MEMORY_UNITS = Map.of(
            "Ki", BigInteger.ONE.shiftLeft(10), "Mi", BigInteger.ONE.shiftLeft(20),
            "Gi", BigInteger.ONE.shiftLeft(30), "Ti", BigInteger.ONE.shiftLeft(40));

    public SandboxConfiguration {
        provider = requireText(provider, "provider");
        provisioning = requireText(provisioning, "provisioning");
        if (!"demand".equals(provisioning)) throw new IllegalArgumentException("provisioning must be demand");
        image = Objects.requireNonNull(image, "image").map(value -> requireText(value, "image"));
        if (("docker".equals(provider) || "kubernetes".equals(provider))
                && (image.isEmpty() || !DIGEST_IMAGE.matcher(image.get()).matches())) {
            throw new IllegalArgumentException(provider + " image must be pinned by sha256 digest");
        }
        cpu = Objects.requireNonNull(cpu, "cpu").stripTrailingZeros();
        if (cpu.signum() <= 0 || cpu.compareTo(BigDecimal.valueOf(256)) > 0 || cpu.scale() > 3) {
            throw new IllegalArgumentException("cpu is outside the supported bound");
        }
        memory = requireText(memory, "memory");
        if (!MEMORY.matcher(memory).matches()) throw new IllegalArgumentException("memory is invalid");
        BigInteger memoryBytes = quantity(memory, MEMORY_UNITS);
        if (memoryBytes.compareTo(BigInteger.ONE.shiftLeft(20)) < 0
                || memoryBytes.compareTo(BigInteger.ONE.shiftLeft(40)) > 0) {
            throw new IllegalArgumentException("memory is outside the supported bound");
        }
        if (pidLimit <= 0 || pidLimit > 4096) throw new IllegalArgumentException("pidLimit is outside the bound");
        networkPolicy = requireText(networkPolicy, "networkPolicy");
        if (!"NONE".equals(networkPolicy)) throw new IllegalArgumentException("networkPolicy must be NONE");
        maxRuntime = requireText(maxRuntime, "maxRuntime");
        if (!RUNTIME.matcher(maxRuntime).matches()) throw new IllegalArgumentException("maxRuntime is invalid");
        BigInteger runtimeMillis = durationMillis(maxRuntime);
        if (runtimeMillis.compareTo(BigInteger.valueOf(7L * 24 * 60 * 60 * 1_000)) > 0) {
            throw new IllegalArgumentException("maxRuntime is outside the supported bound");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static BigInteger quantity(String value, Map<String, BigInteger> units) {
        String suffix = value.length() > 2 ? value.substring(value.length() - 2) : "";
        BigInteger multiplier = units.get(suffix);
        return multiplier == null ? new BigInteger(value)
                : new BigInteger(value.substring(0, value.length() - 2)).multiply(multiplier);
    }

    /** Exact byte count used by provider resource controls. */
    public long memoryBytesExact() {
        return quantity(memory, MEMORY_UNITS).longValueExact();
    }

    /** Exact duration used by provider monotonic runtime controls. */
    public java.time.Duration maxRuntimeDuration() {
        return java.time.Duration.ofMillis(durationMillis(maxRuntime).longValueExact());
    }

    private static BigInteger durationMillis(String value) {
        int unitStart = value.endsWith("ms") ? value.length() - 2 : value.length() - 1;
        BigInteger amount = new BigInteger(value.substring(0, unitStart));
        return switch (value.substring(unitStart)) {
            case "ms" -> amount;
            case "s" -> amount.multiply(BigInteger.valueOf(1_000));
            case "m" -> amount.multiply(BigInteger.valueOf(60_000));
            default -> amount.multiply(BigInteger.valueOf(3_600_000));
        };
    }
}
