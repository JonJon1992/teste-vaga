package dev.challenge.activation.domain;

import java.time.Duration;

public sealed interface ActivationOutcome {

    record Activated() implements ActivationOutcome {
    }

    record Rejected(String reason) implements ActivationOutcome {
    }

    record TemporaryFailure(String reason, Duration retryAfter) implements ActivationOutcome {

        public static TemporaryFailure of(String reason) {
            return new TemporaryFailure(reason, null);
        }
    }
}
