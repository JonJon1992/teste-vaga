package dev.challenge.activation.domain.shared;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class UuidV7Test {

    private static final Instant NOW = Instant.parse("2026-10-26T14:03:11.123Z");

    @Test
    void followsTheRfc9562Layout() {
        UUID uuid = UuidV7.generate(NOW);

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void carriesTheTimestampInTheFirst48Bits() {
        UUID uuid = UuidV7.generate(NOW);

        assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
        assertThat(uuid.toString().replace("-", "")).startsWith("%012x".formatted(NOW.toEpochMilli()));
    }

    @Test
    void sortsByCreationTimeInTheSameOrderAsTheDatabase() {
        List<String> generatedInOrder = IntStream.range(0, 100)
                .mapToObj(millis -> UuidV7.generate(NOW.plusMillis(millis)).toString())
                .toList();

        assertThat(generatedInOrder).isSorted();
    }

    @Test
    void remainsUniqueWithinTheSameMillisecond() {
        Set<UUID> generated = new HashSet<>();
        IntStream.range(0, 10_000).forEach(i -> generated.add(UuidV7.generate(NOW)));

        assertThat(generated).hasSize(10_000);
    }

    @Test
    void rejectsInstantsOutsideTheSupportedRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> UuidV7.generate(Instant.EPOCH.minusMillis(1)));
    }
}
