package dev.challenge.activation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.random.RandomGenerator;

public final class RetryPolicy {

    private static final int MAX_EXPONENT = 30;

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final RandomGenerator random;

    public RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, RandomGenerator random) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts deve ser maior que zero");
        }
        if (!initialBackoff.isPositive() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("backoff inicial deve ser positivo e menor ou igual ao máximo");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.random = Objects.requireNonNull(random, "random");
    }

    public boolean allowsRetryAfter(int attempts) {
        return attempts < maxAttempts;
    }

    public Instant nextAttemptAt(int attempts, Duration retryAfter, Instant now) {
        Duration delay = withJitter(exponentialBackoff(attempts));
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter;
        }
        return now.plus(delay);
    }

    private Duration exponentialBackoff(int attempts) {
        int exponent = Math.clamp(attempts - 1L, 0, MAX_EXPONENT);
        Duration backoff = initialBackoff.multipliedBy(1L << exponent);
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }

    private Duration withJitter(Duration backoff) {
        long half = backoff.toMillis() / 2;
        return Duration.ofMillis(half + random.nextLong(half + 1));
    }
}
