package dev.challenge.activation.application;

import dev.challenge.activation.domain.activation.ActivationRequest;

public sealed interface CreationResult {

    ActivationRequest request();

    record Created(ActivationRequest request) implements CreationResult {
    }

    record AlreadyExists(ActivationRequest request) implements CreationResult {
    }
}
