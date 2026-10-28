package dev.challenge.activation.domain;

public record ActivationRequestFilter(String clientId, ActivationStatus status, String customerId) {

    public ActivationRequestFilter {
        Guard.notBlank(clientId, "clientId");
    }
}
