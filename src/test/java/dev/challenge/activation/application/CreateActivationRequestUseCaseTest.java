package dev.challenge.activation.application;

import dev.challenge.activation.application.CreationResult.AlreadyExists;
import dev.challenge.activation.application.CreationResult.Created;
import dev.challenge.activation.domain.activation.ActivationDetails;
import dev.challenge.activation.domain.activation.ActivationRequest;
import dev.challenge.activation.domain.activation.ActivationRequestRepository;
import dev.challenge.activation.domain.activation.ActivationStatus;
import dev.challenge.activation.domain.activation.DuplicateActivationRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateActivationRequestUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:03:11Z");
    private static final String CLIENT = "system-a";
    private static final ActivationDetails DETAILS = new ActivationDetails("EXT-12345", "CUST-10001", "INTERNET-500");
    private static final ActivationDetails DIVERGENT_DETAILS =
            new ActivationDetails("EXT-12345", "CUST-10001", "TV-HD");

    @Mock
    private ActivationRequestRepository repository;

    private CreateActivationRequestUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateActivationRequestUseCase(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void registersANewPendingRequestWhenTheExternalIdIsUnknown() {
        when(repository.findByExternalId(CLIENT, "EXT-12345")).thenReturn(Optional.empty());
        when(repository.add(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CreationResult result = useCase.create(command(DETAILS));

        assertThat(result).isInstanceOf(Created.class);
        assertThat(result.request().getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(result.request().details()).isEqualTo(DETAILS);
        assertThat(result.request().getCreatedAt()).isEqualTo(NOW);
        assertThat(result.request().getCorrelationId()).isEqualTo("corr-1");
    }

    @Test
    void returnsTheExistingRequestOnALegitimateRetry() {
        ActivationRequest existing = existingRequest(DETAILS);
        when(repository.findByExternalId(CLIENT, "EXT-12345")).thenReturn(Optional.of(existing));

        CreationResult result = useCase.create(command(DETAILS));

        assertThat(result).isEqualTo(new AlreadyExists(existing));
        verify(repository, never()).add(any());
    }

    @Test
    void rejectsARetryWithDifferentDetails() {
        when(repository.findByExternalId(CLIENT, "EXT-12345")).thenReturn(Optional.of(existingRequest(DETAILS)));

        assertThatThrownBy(() -> useCase.create(command(DIVERGENT_DETAILS)))
                .isInstanceOf(ExternalIdConflictException.class)
                .hasMessageContaining("EXT-12345");
        verify(repository, never()).add(any());
    }

    @Test
    void returnsTheWinnerWhenAConcurrentRequestInsertsFirst() {
        ActivationRequest winner = existingRequest(DETAILS);
        when(repository.findByExternalId(CLIENT, "EXT-12345"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.add(any())).thenThrow(new DuplicateActivationRequestException("EXT-12345", null));

        CreationResult result = useCase.create(command(DETAILS));

        assertThat(result).isEqualTo(new AlreadyExists(winner));
    }

    @Test
    void rejectsTheLoserOfAConcurrentInsertWhenDetailsDiverge() {
        when(repository.findByExternalId(CLIENT, "EXT-12345"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingRequest(DETAILS)));
        when(repository.add(any())).thenThrow(new DuplicateActivationRequestException("EXT-12345", null));

        assertThatThrownBy(() -> useCase.create(command(DIVERGENT_DETAILS)))
                .isInstanceOf(ExternalIdConflictException.class);
    }

    private static CreateActivationRequestCommand command(ActivationDetails details) {
        return new CreateActivationRequestCommand(CLIENT, details, "corr-1");
    }

    private static ActivationRequest existingRequest(ActivationDetails details) {
        return ActivationRequest.register(CLIENT, details, "corr-0", NOW.minusSeconds(60));
    }
}
