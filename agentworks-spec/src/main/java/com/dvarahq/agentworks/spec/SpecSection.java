package com.dvarahq.agentworks.spec;

/** AgentSpec area addressed by a diagnostic, derived from its JSON Pointer. */
public enum SpecSection {
    DOCUMENT, METADATA, SPEC, EXECUTOR, MODELS, INSTRUCTIONS, EXECUTION, CONTEXT, CAPABILITIES, SANDBOX, MEMORY, LIMITS;

    static SpecSection of(String pointer) {
        String[] tokens = pointer.split("/", -1);
        if (tokens.length < 2 || tokens[1].isEmpty()) return DOCUMENT;
        if (tokens[1].equals("metadata")) return METADATA;
        if (!tokens[1].equals("spec")) return DOCUMENT;
        if (tokens.length < 3) return SPEC;
        return switch (tokens[2]) {
            case "executor" -> EXECUTOR;
            case "models" -> MODELS;
            case "instructions" -> INSTRUCTIONS;
            case "execution" -> EXECUTION;
            case "context" -> CONTEXT;
            case "capabilityPacks", "skills" -> CAPABILITIES;
            case "sandbox" -> SANDBOX;
            case "memory" -> MEMORY;
            case "limits" -> LIMITS;
            default -> SPEC;
        };
    }
}
