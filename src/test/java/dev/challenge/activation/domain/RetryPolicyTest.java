package dev.challenge.activation.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    private static final RandomGenerator MIN_JITTER = () -> 0L;
    private static final RandomGenerator MAX_JITTER = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0L;
        }

        @Override
        public long nextLong(long bound) {
            return bound - 1;
        }
    };

    private final RetryPolicy policy = policyWith(MAX_JITTER);

    @Test
    void allowsRetryUntilMaxAttemptsIsReached() {
        assertThat(policy.allowsRetryAfter(4)).isTrue();
        assertThat(policy.allowsRetryAfter(5)).isFalse();
    }

    @Test
    void doublesTheBackoffOnEachAttemptUpToTheMaximum() {
        assertThat(delayAfter(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(delayAfter(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(delayAfter(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(delayAfter(20)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void appliesJitterOfUpToHalfTheBackoff() {
        Instant next = policyWith(MIN_JITTER).nextAttemptAt(3, null, NOW);

        assertThat(Duration.between(NOW, next)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void honorsRetryAfterWhenItIsLongerThanTheBackoff() {
        Instant next = policy.nextAttemptAt(1, Duration.ofMinutes(1), NOW);

        assertThat(next).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
    }

    @Test
    void ignoresRetryAfterShorterThanTheBackoff() {
        Instant next = policy.nextAttemptAt(3, Duration.ofSeconds(1), NOW);

        assertThat(next).isEqualTo(NOW.plusSeconds(8));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(2), MIN_JITTER));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(5), Duration.ofSeconds(1), MIN_JITTER));
    }

    private static RetryPolicy policyWith(RandomGenerator random) {
        return new RetryPolicy(5, Duration.ofSeconds(2), Duration.ofSeconds(30), random);
    }

    private Duration delayAfter(int attempts) {
        return Duration.between(NOW, policy.nextAttemptAt(attempts, null, NOW));
    }
}
