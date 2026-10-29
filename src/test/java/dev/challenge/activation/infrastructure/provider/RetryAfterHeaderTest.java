package dev.challenge.activation.infrastructure.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryAfterHeaderTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");

    @Test
    void parsesDelayInSeconds() {
        assertThat(RetryAfterHeader.parse("120", NOW)).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void parsesHttpDateRelativeToNow() {
        assertThat(RetryAfterHeader.parse("Mon, 26 Oct 2026 14:00:45 GMT", NOW)).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void neverReturnsANegativeDelay() {
        assertThat(RetryAfterHeader.parse("Mon, 26 Oct 2026 13:00:00 GMT", NOW)).isZero();
        assertThat(RetryAfterHeader.parse("-5", NOW)).isZero();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"soon", "26/10/2026"})
    void ignoresMissingOrInvalidValues(String value) {
        assertThat(RetryAfterHeader.parse(value, NOW)).isNull();
    }
}
