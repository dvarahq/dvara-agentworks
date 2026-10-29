package com.dvarahq.agentworks.testing.simulation;

/** A deterministic fault selected by dependency identity, operation, occurrence, and optional exact seed. */
public record SimulationFault(String id, String dependencyClass, String dependency, String operation,
        long occurrence, Long seed, String failureCode) {
    public SimulationFault {
        id = required(id, "id"); dependencyClass = required(dependencyClass, "dependencyClass");
        dependency = required(dependency, "dependency"); operation = required(operation, "operation");
        failureCode = required(failureCode, "failureCode");
        if (occurrence < 1) throw new IllegalArgumentException("occurrence must be positive");
    }
    public static SimulationFault model(String id, String model, long occurrence, String failureCode) {
        return new SimulationFault(id, "model", model, "invoke", occurrence, null, failureCode);
    }
    public static SimulationFault tool(String id, String tool, long occurrence, String failureCode) {
        return new SimulationFault(id, "tool", tool, "invoke", occurrence, null, failureCode);
    }
    public SimulationFault onlyForSeed(long value) {
        return new SimulationFault(id, dependencyClass, dependency, operation, occurrence, value, failureCode);
    }
    boolean matches(DependencyCall call, long actualOccurrence) {
        return matches(dependencyClass, call.dependencyClass()) && matches(dependency, call.dependency())
                && matches(operation, call.operation()) && occurrence == actualOccurrence
                && (seed == null || seed == call.seed());
    }
    private static boolean matches(String configured, String actual) { return configured.equals("*") || configured.equals(actual); }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
