package dev.challenge.activation.infrastructure.scheduling;

import dev.challenge.activation.application.DispatchSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ProcessingConfigurationTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final ProcessingConfiguration configuration = new ProcessingConfiguration();

    @Test
    void acceptsALeaseLongerThanTheSlowestProviderCall() {
        DispatchSettings settings = configuration.dispatchSettings(withLease(Duration.ofSeconds(30)),
                CONNECT_TIMEOUT, READ_TIMEOUT);

        assertThat(settings.lease()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void refusesToStartWhenALeaseCouldExpireDuringAProviderCall() {
        assertThatIllegalStateException()
                .isThrownBy(() -> configuration.dispatchSettings(withLease(Duration.ofSeconds(7)),
                        CONNECT_TIMEOUT, READ_TIMEOUT))
                .withMessageContaining("activation.processing.lease (PT7S)")
                .withMessageContaining("leitura do provedor (PT7S)");
    }

    private static ProcessingProperties withLease(Duration lease) {
        return new ProcessingProperties(10, lease, 5, Duration.ofSeconds(2), Duration.ofMinutes(5));
    }
}
