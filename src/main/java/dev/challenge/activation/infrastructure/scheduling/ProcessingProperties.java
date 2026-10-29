package dev.challenge.activation.infrastructure.scheduling;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("activation.processing")
record ProcessingProperties(
        @Positive int batchSize,
        @NotNull Duration lease,
        @Positive int maxAttempts,
        @NotNull Duration initialBackoff,
        @NotNull Duration maxBackoff) {
}
