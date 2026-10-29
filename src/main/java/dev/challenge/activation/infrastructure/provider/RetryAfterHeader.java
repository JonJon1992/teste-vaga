package dev.challenge.activation.infrastructure.provider;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

final class RetryAfterHeader {

    private RetryAfterHeader() {
    }

    static Duration parse(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return nonNegative(Duration.ofSeconds(Long.parseLong(value.trim())));
        } catch (NumberFormatException notDeltaSeconds) {
            return parseHttpDate(value.trim(), now);
        }
    }

    private static Duration parseHttpDate(String value, Instant now) {
        try {
            Instant date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return nonNegative(Duration.between(now, date));
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    private static Duration nonNegative(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
