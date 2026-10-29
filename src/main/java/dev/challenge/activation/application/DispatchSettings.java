package dev.challenge.activation.application;

import java.time.Duration;

public record DispatchSettings(int batchSize, Duration lease) {

    public DispatchSettings {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize deve ser maior que zero");
        }
        if (lease == null || !lease.isPositive()) {
            throw new IllegalArgumentException("lease deve ser positivo");
        }
    }
}
