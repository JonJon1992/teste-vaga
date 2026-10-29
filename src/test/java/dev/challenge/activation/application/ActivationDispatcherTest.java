package dev.challenge.activation.application;

import dev.challenge.activation.domain.activation.ActivationDetails;
import dev.challenge.activation.domain.activation.ActivationOutcome.Activated;
import dev.challenge.activation.domain.activation.ActivationOutcome.Rejected;
import dev.challenge.activation.domain.activation.ActivationOutcome.TemporaryFailure;
import dev.challenge.activation.domain.activation.ActivationProvider;
import dev.challenge.activation.domain.activation.ActivationRequest;
import dev.challenge.activation.domain.activation.ActivationRequestRepository;
import dev.challenge.activation.domain.activation.ActivationStatus;
import dev.challenge.activation.domain.activation.RetryPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final RetryPolicy RETRY_POLICY =
            new RetryPolicy(3, Duration.ofSeconds(2), Duration.ofMinutes(1), () -> 0L);

    @Mock
    private ActivationRequestRepository repository;

    @Mock
    private ActivationProvider provider;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ActivationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ActivationDispatcher(repository, provider, RETRY_POLICY, new DispatchSettings(10, LEASE),
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);
    }

    @Nested
    class DispatchingDueRequests {

        private final ActivationRequest request = pendingRequest();

        @BeforeEach
        void setUp() {
            when(repository.lockDueForDispatch(NOW, 10)).thenReturn(List.of(request));
        }

        @Test
        void completesTheRequestWhenTheProviderActivatesIt() {
            when(provider.activate(request)).thenReturn(new Activated());

            int dispatched = dispatcher.dispatchDueRequests();

            assertThat(dispatched).isEqualTo(1);
            assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
            assertThat(request.getAttempts()).isEqualTo(1);
            verify(repository, times(2)).save(request);
            assertThat(finishedCount("COMPLETED")).isEqualTo(1);
        }

        @Test
        void failsTheRequestWhenTheProviderRejectsIt() {
            when(provider.activate(request)).thenReturn(new Rejected("Provedor rejeitou a ativação (HTTP 400)"));

            dispatcher.dispatchDueRequests();

            assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
            assertThat(request.getFailureReason()).isEqualTo("Provedor rejeitou a ativação (HTTP 400)");
            assertThat(finishedCount("FAILED")).isEqualTo(1);
        }

        @Test
        void schedulesANewAttemptOnTemporaryFailure() {
            when(provider.activate(request)).thenReturn(TemporaryFailure.of("Erro no provedor (HTTP 500)"));

            dispatcher.dispatchDueRequests();

            assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
            assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
            assertThat(meterRegistry.find(ActivationDispatcher.FINISHED_METRIC).counters()).isEmpty();
        }

        @Test
        void treatsAnUnexpectedProviderErrorAsTemporary() {
            when(provider.activate(request)).thenThrow(new IllegalStateException("serialization failed"));

            dispatcher.dispatchDueRequests();

            assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
            assertThat(request.getFailureReason()).isEqualTo("Erro inesperado ao chamar o provedor");
        }

        @Test
        void discardsTheOutcomeWhenAnotherProcessUpdatedTheRequest() {
            when(provider.activate(request)).thenReturn(new Activated());
            when(repository.save(request))
                    .thenReturn(request)
                    .thenThrow(new OptimisticLockingFailureException("stale version"));

            assertThatNoException().isThrownBy(() -> dispatcher.dispatchDueRequests());
            assertThat(finishedCount("COMPLETED")).isZero();
        }
    }

    @Test
    void doesNotCallTheProviderWhenNothingIsDue() {
        when(repository.lockDueForDispatch(NOW, 10)).thenReturn(List.of());

        assertThat(dispatcher.dispatchDueRequests()).isZero();
        verify(provider, never()).activate(any());
    }

    @Test
    void releasesRequestsWhoseLeaseExpiredSoTheyCanBeRetried() {
        ActivationRequest stuck = pendingRequest();
        stuck.startProcessing(LEASE, NOW.minus(LEASE));
        when(repository.lockWithExpiredLease(NOW, 10)).thenReturn(List.of(stuck));

        int recovered = dispatcher.recoverExpiredLeases();

        assertThat(recovered).isEqualTo(1);
        assertThat(stuck.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(stuck.getFailureReason()).contains("sem confirmação do provedor");
        verify(repository).save(stuck);
        assertThat(meterRegistry.find(ActivationDispatcher.FINISHED_METRIC).counters()).isEmpty();
    }

    @Test
    void countsARequestThatFailsWhenItsLastLeaseExpires() {
        ActivationRequest stuck = pendingRequest();
        stuck.startProcessing(LEASE, NOW.minus(LEASE));
        stuck.applyOutcome(TemporaryFailure.of("HTTP 500"), RETRY_POLICY, NOW.minus(LEASE));
        stuck.startProcessing(LEASE, NOW.minus(LEASE));
        stuck.applyOutcome(TemporaryFailure.of("HTTP 500"), RETRY_POLICY, NOW.minus(LEASE));
        stuck.startProcessing(LEASE, NOW.minus(LEASE));
        when(repository.lockWithExpiredLease(NOW, 10)).thenReturn(List.of(stuck));

        dispatcher.recoverExpiredLeases();

        assertThat(stuck.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(finishedCount("FAILED")).isEqualTo(1);
    }

    private double finishedCount(String status) {
        Counter counter = meterRegistry.find(ActivationDispatcher.FINISHED_METRIC).tag("status", status).counter();
        return counter == null ? 0 : counter.count();
    }

    private static ActivationRequest pendingRequest() {
        return ActivationRequest.register("system-a", new ActivationDetails("EXT-1", "CUST-1", "INTERNET-500"),
                "corr-12345678", NOW.minusSeconds(5));
    }
}
