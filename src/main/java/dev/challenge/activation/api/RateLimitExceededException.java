package dev.challenge.activation.api;

import java.time.Duration;

class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    RateLimitExceededException(Duration retryAfter) {
        super("Limite de requisições excedido para este consumidor. Tente novamente em %d segundo(s)."
                .formatted(retryAfter.toSeconds()));
        this.retryAfter = retryAfter;
    }

    Duration retryAfter() {
        return retryAfter;
    }
}
