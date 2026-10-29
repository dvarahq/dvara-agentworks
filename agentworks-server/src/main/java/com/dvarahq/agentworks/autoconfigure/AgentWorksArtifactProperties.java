package com.dvarahq.agentworks.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("agentworks.artifacts")
public final class AgentWorksArtifactProperties {
    private Path root = Path.of("data", "artifacts");
    private DataSize maximumSize = DataSize.ofMegabytes(100);
    private int computeMaximumInputs = 32;
    private DataSize computeMaximumInputSize = DataSize.ofMegabytes(64);
    private DataSize computeMaximumOutputSize = DataSize.ofMegabytes(16);
    private Duration computeTimeout = Duration.ofSeconds(30);
    private int maximumArchiveEntries = 1_000;
    private DataSize maximumArchiveExpandedSize = DataSize.ofMegabytes(256);
    private int maximumArchiveExpansionRatio = 100;
    private Duration stagingLease = Duration.ofMinutes(5);
    private Duration deletionGrace = Duration.ofHours(24);
    private Duration maintenanceInterval = Duration.ofMinutes(5);
    private int maintenanceBatchSize = 200;

    public Path getRoot() {
        return root;
    }

    public void setRoot(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public DataSize getMaximumSize() {
        return maximumSize;
    }

    public void setMaximumSize(DataSize maximumSize) {
        this.maximumSize = Objects.requireNonNull(maximumSize, "maximumSize");
    }

    public int getComputeMaximumInputs() { return computeMaximumInputs; }

    public void setComputeMaximumInputs(int computeMaximumInputs) {
        this.computeMaximumInputs = computeMaximumInputs;
    }

    public DataSize getComputeMaximumInputSize() { return computeMaximumInputSize; }

    public void setComputeMaximumInputSize(DataSize computeMaximumInputSize) {
        this.computeMaximumInputSize = Objects.requireNonNull(computeMaximumInputSize, "computeMaximumInputSize");
    }

    public DataSize getComputeMaximumOutputSize() { return computeMaximumOutputSize; }

    public void setComputeMaximumOutputSize(DataSize computeMaximumOutputSize) {
        this.computeMaximumOutputSize = Objects.requireNonNull(computeMaximumOutputSize, "computeMaximumOutputSize");
    }

    public Duration getComputeTimeout() { return computeTimeout; }

    public void setComputeTimeout(Duration computeTimeout) {
        this.computeTimeout = Objects.requireNonNull(computeTimeout, "computeTimeout");
    }

    public int getMaximumArchiveEntries() { return maximumArchiveEntries; }
    public void setMaximumArchiveEntries(int value) { maximumArchiveEntries = value; }

    public DataSize getMaximumArchiveExpandedSize() { return maximumArchiveExpandedSize; }
    public void setMaximumArchiveExpandedSize(DataSize value) {
        maximumArchiveExpandedSize = Objects.requireNonNull(value, "maximumArchiveExpandedSize");
    }

    public int getMaximumArchiveExpansionRatio() { return maximumArchiveExpansionRatio; }
    public void setMaximumArchiveExpansionRatio(int value) { maximumArchiveExpansionRatio = value; }

    public Duration getStagingLease() { return stagingLease; }
    public void setStagingLease(Duration value) { stagingLease = Objects.requireNonNull(value, "stagingLease"); }

    public Duration getDeletionGrace() { return deletionGrace; }
    public void setDeletionGrace(Duration value) { deletionGrace = Objects.requireNonNull(value, "deletionGrace"); }

    public Duration getMaintenanceInterval() { return maintenanceInterval; }
    public void setMaintenanceInterval(Duration value) {
        maintenanceInterval = Objects.requireNonNull(value, "maintenanceInterval");
    }

    public int getMaintenanceBatchSize() { return maintenanceBatchSize; }
    public void setMaintenanceBatchSize(int value) { maintenanceBatchSize = value; }
}
