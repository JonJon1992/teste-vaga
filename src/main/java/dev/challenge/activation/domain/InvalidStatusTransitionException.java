package dev.challenge.activation.domain;

import java.util.UUID;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(UUID requestId, ActivationStatus from, ActivationStatus to) {
        super("Solicitação %s não pode passar de %s para %s".formatted(requestId, from, to));
    }
}
