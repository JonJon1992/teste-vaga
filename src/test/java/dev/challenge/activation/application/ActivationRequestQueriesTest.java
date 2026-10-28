package dev.challenge.activation.application;

import dev.challenge.activation.domain.activation.ActivationDetails;
import dev.challenge.activation.domain.activation.ActivationRequest;
import dev.challenge.activation.domain.activation.ActivationRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationRequestQueriesTest {

    private static final ActivationRequest REQUEST = ActivationRequest.register("system-a",
            new ActivationDetails("EXT-1", "CUST-1", "INTERNET-500"), null, Instant.parse("2026-10-26T14:00:00Z"));

    @Mock
    private ActivationRequestRepository repository;

    @InjectMocks
    private ActivationRequestQueries queries;

    @Test
    void returnsARequestOwnedByTheClient() {
        when(repository.findById(REQUEST.getId())).thenReturn(Optional.of(REQUEST));

        assertThat(queries.findById(REQUEST.getId(), "system-a")).isSameAs(REQUEST);
    }

    @Test
    void reportsNotFoundWhenTheRequestDoesNotExist() {
        UUID unknownId = UUID.randomUUID();
        when(repository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queries.findById(unknownId, "system-a"))
                .isInstanceOf(ActivationRequestNotFoundException.class);
    }

    @Test
    void hidesRequestsOwnedByAnotherClient() {
        when(repository.findById(REQUEST.getId())).thenReturn(Optional.of(REQUEST));

        assertThatThrownBy(() -> queries.findById(REQUEST.getId(), "system-b"))
                .isInstanceOf(ActivationRequestNotFoundException.class);
    }
}
