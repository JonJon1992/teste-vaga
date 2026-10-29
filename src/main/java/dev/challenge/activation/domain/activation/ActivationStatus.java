package dev.challenge.activation.domain.activation;

public enum ActivationStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(ActivationStatus target) {
        return switch (this) {
            case PENDING -> target == PROCESSING;
            case PROCESSING -> target == COMPLETED || target == FAILED || target == PENDING;
            case COMPLETED, FAILED -> false;
        };
    }

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED;
    }
}
