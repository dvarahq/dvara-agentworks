package com.dvarahq.agentworks.gateway;

import java.util.Objects;

/**
 * Normalized token usage. A reported value is exactly what the provider reported; a missing breakdown is {@code null},
 * never zero. Estimation and cost are #85.
 */
public record ModelUsage(UsageStatus status, Long inputTokens, Long outputTokens, Long totalTokens,
        Long cachedInputTokens, Long reasoningTokens, Long toolTokens, Cost cost) {

    /** Signed authoritative-minus-original token differences for delayed usage diagnostics. */
    public record TokenDiscrepancy(Long inputTokens, Long outputTokens, Long totalTokens,
            Long cachedInputTokens, Long reasoningTokens, Long toolTokens) { }

    /** The authoritative normalized usage and its discrepancy from the initially recorded value. */
    public record Reconciliation(ModelUsage usage, TokenDiscrepancy discrepancy) {
        public Reconciliation {
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(discrepancy, "discrepancy");
        }
    }

    /** Optional normalized cost evidence. The catalog/version names the immutable pricing input used. */
    public record Cost(java.math.BigDecimal amount, String currency, String catalog, String version) {
        public Cost {
            Objects.requireNonNull(amount, "amount");
            if (amount.signum() < 0) throw new IllegalArgumentException("cost must not be negative");
            Objects.requireNonNull(currency, "currency");
            if (!currency.matches("^[A-Z]{3}$")) {
                throw new IllegalArgumentException("currency must be an uppercase ISO 4217 code");
            }
            GatewayDescriptor.requireText(catalog, "catalog");
            GatewayDescriptor.requireText(version, "version");
        }
    }

    public enum UsageStatus {
        /** The provider reported usage for this invocation. */
        REPORTED,
        /** Provider omitted usage; Core derived a conservative count without retaining prompt or response content. */
        ESTIMATED,
        /** A provider supplied a later authoritative record to be reconciled against the original estimate. */
        RECONCILED,
        /** The provider did not report usage; every count is {@code null}. */
        UNREPORTED
    }

    public static final ModelUsage UNREPORTED = new ModelUsage(UsageStatus.UNREPORTED, null, null, null, null, null,
            null, null);

    /** Compatibility constructor for provider-reported usage without tool-token or cost extensions. */
    public ModelUsage(UsageStatus status, Long inputTokens, Long outputTokens, Long totalTokens,
            Long cachedInputTokens, Long reasoningTokens) {
        this(status, inputTokens, outputTokens, totalTokens, cachedInputTokens, reasoningTokens, null, null);
    }

    public ModelUsage {
        Objects.requireNonNull(status, "status");
        if (status == UsageStatus.UNREPORTED && (inputTokens != null || outputTokens != null || totalTokens != null
                || cachedInputTokens != null || reasoningTokens != null || toolTokens != null || cost != null)) {
            throw new IllegalArgumentException("unreported usage carries no counts");
        }
        for (Long count : new Long[]{inputTokens, outputTokens, totalTokens, cachedInputTokens, reasoningTokens,
                toolTokens}) {
            if (count != null && count < 0) {
                throw new IllegalArgumentException("token counts must not be negative");
            }
        }
    }

    /** Reported usage; {@code totalTokens} is derived when the provider omits it. */
    public static ModelUsage reported(long inputTokens, long outputTokens, Long totalTokens, Long cachedInputTokens,
            Long reasoningTokens) {
        return new ModelUsage(UsageStatus.REPORTED, inputTokens, outputTokens,
                totalTokens == null ? Math.addExact(inputTokens, outputTokens) : totalTokens,
                cachedInputTokens, reasoningTokens, null, null);
    }

    public static ModelUsage estimated(long inputTokens, long outputTokens, long toolTokens) {
        return new ModelUsage(UsageStatus.ESTIMATED, inputTokens, outputTokens,
                Math.addExact(Math.addExact(inputTokens, outputTokens), toolTokens), null, null, toolTokens, null);
    }

    /**
     * Replaces an initial estimate/unreported value with a delayed authoritative provider record. The returned
     * discrepancy is diagnostic evidence; callers charge the reconciled value once, never both records.
     */
    public static Reconciliation reconcile(ModelUsage original, ModelUsage authoritative) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(authoritative, "authoritative");
        if (authoritative.status != UsageStatus.REPORTED) {
            throw new IllegalArgumentException("authoritative usage must have REPORTED status");
        }
        ModelUsage reconciled = new ModelUsage(UsageStatus.RECONCILED, authoritative.inputTokens,
                authoritative.outputTokens, authoritative.totalTokens, authoritative.cachedInputTokens,
                authoritative.reasoningTokens, authoritative.toolTokens, authoritative.cost);
        return new Reconciliation(reconciled, new TokenDiscrepancy(
                delta(original.inputTokens, reconciled.inputTokens),
                delta(original.outputTokens, reconciled.outputTokens),
                delta(original.totalTokens, reconciled.totalTokens),
                delta(original.cachedInputTokens, reconciled.cachedInputTokens),
                delta(original.reasoningTokens, reconciled.reasoningTokens),
                delta(original.toolTokens, reconciled.toolTokens)));
    }

    private static Long delta(Long original, Long authoritative) {
        return original == null || authoritative == null ? null : Math.subtractExact(authoritative, original);
    }
}
