package com.dvarahq.agentworks.domain.run;

import java.util.Objects;

/**
 * The authoritative store refused a write, for example because the lease is stale, the cohort does not admit the
 * work, or a transition is illegal. {@code code} is a stable machine value such as {@code STALE_LEASE} or
 * {@code COHORT_NOT_ADMITTING}; {@code detail} is an optional qualifier such as the cohort state.
 */
public final class RunAuthorityException extends RuntimeException {
    private final String code;
    private final String detail;

    public RunAuthorityException(String code, String detail, Throwable cause) {
        super(detail == null ? code : code + " " + detail, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.detail = detail;
    }

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }
}
