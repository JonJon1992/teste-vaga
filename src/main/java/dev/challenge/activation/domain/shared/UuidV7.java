package dev.challenge.activation.domain.shared;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long MAX_TIMESTAMP = (1L << 48) - 1;
    private static final long VERSION_7 = 0x7L << 12;
    private static final long RAND_A_MASK = 0xFFFL;
    private static final long VARIANT_RFC_9562 = 0x8000_0000_0000_0000L;
    private static final long RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    private UuidV7() {
    }

    public static UUID generate(Instant timestamp) {
        long unixMillis = timestamp.toEpochMilli();
        if (unixMillis < 0 || unixMillis > MAX_TIMESTAMP) {
            throw new IllegalArgumentException("Instante fora do intervalo suportado pelo UUID v7: " + timestamp);
        }
        long mostSignificantBits = (unixMillis << 16) | VERSION_7 | (RANDOM.nextLong() & RAND_A_MASK);
        long leastSignificantBits = VARIANT_RFC_9562 | (RANDOM.nextLong() & RAND_B_MASK);
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
