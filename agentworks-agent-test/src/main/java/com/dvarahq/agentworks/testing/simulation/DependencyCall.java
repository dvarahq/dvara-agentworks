package com.dvarahq.agentworks.testing.simulation;

/** Provider-neutral dependency call observed by the simulation fault SPI. */
public record DependencyCall(String dependencyClass, String dependency, String operation, long seed) {
    public DependencyCall {
        dependencyClass = required(dependencyClass, "dependencyClass");
        dependency = required(dependency, "dependency");
        operation = required(operation, "operation");
    }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
