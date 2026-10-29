package com.dvarahq.agentworks.spec;

/** Stable, machine-readable AgentSpec validation outcome categories. */
public enum DiagnosticCode {
    /** The bytes are not a single well-formed YAML or JSON document. */
    MALFORMED_DOCUMENT,
    /** The document exceeds the accepted size, depth, or token bounds. */
    DOCUMENT_TOO_LARGE,
    /** {@code apiVersion} names a version this installation does not implement. */
    UNSUPPORTED_API_VERSION,
    /** {@code kind} is not {@code Agent}. */
    UNSUPPORTED_KIND,
    REQUIRED_FIELD_MISSING,
    /** A field is not defined by the declared {@code apiVersion}; it is rejected rather than silently dropped. */
    UNKNOWN_FIELD,
    /** A field that belongs to another lifecycle artifact or to tenancy authority. */
    FORBIDDEN_FIELD,
    TYPE_MISMATCH,
    INVALID_VALUE,
    OUT_OF_RANGE,
    DUPLICATE_ENTRY
}
