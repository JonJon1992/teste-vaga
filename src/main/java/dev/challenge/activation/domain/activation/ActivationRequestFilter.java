package dev.challenge.activation.domain.activation;

import dev.challenge.activation.domain.shared.Guard;

public record ActivationRequestFilter(String clientId, ActivationStatus status, String customerId) {

    public ActivationRequestFilter {
        Guard.notBlank(clientId, "clientId");
    }
}
