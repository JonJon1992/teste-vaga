package dev.challenge.activation.application;

import dev.challenge.activation.domain.activation.ActivationDetails;

public record CreateActivationRequestCommand(String clientId, ActivationDetails details, String correlationId) {
}
