package com.dvarahq.agentworks.exposure;

public record DeliveryResult(Status status, String externalDeliveryId, String detail) {
    public enum Status { DELIVERED, RETRYABLE, REFUSED, DEGRADED }
}
