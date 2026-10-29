package dev.challenge.activation.domain.activation;

import dev.challenge.activation.domain.activation.ActivationOutcome.Activated;
import dev.challenge.activation.domain.activation.ActivationOutcome.Rejected;
import dev.challenge.activation.domain.activation.ActivationOutcome.TemporaryFailure;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(5);
    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final ActivationDetails DETAILS = new ActivationDetails("EXT-12345", "CUST-10001", "INTERNET-500");
    private static final RetryPolicy RETRY_POLICY =
            new RetryPolicy(3, Duration.ofSeconds(2), Duration.ofSeconds(30), () -> 0L);

    @Test
    void registersAPendingRequestReadyToBeDispatched() {
        ActivationRequest request = ActivationRequest.register("system-a", DETAILS, "corr-1", NOW);

        assertThat(request.getId()).isNotNull();
        assertThat(request.getId().version()).isEqualTo(7);
        assertThat(request.getId().getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.details()).isEqualTo(DETAILS);
        assertThat(request.getAttempts()).isZero();
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(request.getCreatedAt()).isEqualTo(NOW);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void requiresAllActivationDetails() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ActivationDetails("EXT-1", " ", "INTERNET-500"))
                .withMessageContaining("customerId");
    }

    @Test
    void comparesDetailsToDetectConflictingRetries() {
        ActivationRequest request = ActivationRequest.register("system-a", DETAILS, null, NOW);

        assertThat(request.hasSameDetailsAs(DETAILS)).isTrue();
        assertThat(request.hasSameDetailsAs(new ActivationDetails("EXT-12345", "CUST-10001", "TV-HD"))).isFalse();
    }

    @Test
    void belongsOnlyToTheClientThatRegisteredIt() {
        ActivationRequest request = ActivationRequest.register("system-a", DETAILS, null, NOW);

        assertThat(request.isOwnedBy("system-a")).isTrue();
        assertThat(request.isOwnedBy("system-b")).isFalse();
    }

    @Test
    void startsProcessingCountingTheAttemptAndReservingALease() {
        ActivationRequest request = ActivationRequest.register("system-a", DETAILS, null, NOW);

        request.startProcessing(LEASE, LATER);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isEqualTo(LATER.plus(LEASE));
        assertThat(request.getUpdatedAt()).isEqualTo(LATER);
    }

    @Nested
    class WhenTheProviderAnswers {

        private final ActivationRequest request = processingRequest();

        @Test
        void completesOnSuccess() {
            request.applyOutcome(new Activated(), RETRY_POLICY, LATER);

            assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
            assertThat(request.getNextAttemptAt()).isNull();
            assertThat(request.getFailureReason()).isNull();
        }

        @Test
        void failsWithoutRetryWhenRejected() {
            request.applyOutcome(new Rejected("Produto inexistente"), RETRY_POLICY, LATER);

            assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
            assertThat(request.getFailureReason()).isEqualTo("Produto inexistente");
            assertThat(request.getNextAttemptAt()).isNull();
        }

        @Test
        void goesBackToPendingWithANewScheduleOnTemporaryFailure() {
            request.applyOutcome(TemporaryFailure.of("HTTP 500"), RETRY_POLICY, LATER);

            assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
            assertThat(request.getFailureReason()).isEqualTo("HTTP 500");
            assertThat(request.getNextAttemptAt()).isAfter(LATER);
        }

        @Test
        void failsWhenTheRetryLimitIsReached() {
            exhaustAttempts(request);

            request.applyOutcome(TemporaryFailure.of("timeout"), RETRY_POLICY, LATER);

            assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
            assertThat(request.getAttempts()).isEqualTo(3);
            assertThat(request.getFailureReason()).contains("Limite de 3 tentativas").contains("timeout");
        }

        @Test
        void truncatesLongFailureReasons() {
            request.applyOutcome(new Rejected("x".repeat(1_000)), RETRY_POLICY, LATER);

            assertThat(request.getFailureReason()).hasSize(500);
        }
    }

    @Nested
    class WhenTheLeaseExpires {

        private final ActivationRequest request = processingRequest();
        private final Instant afterLease = LATER.plus(LEASE);

        @Test
        void returnsToPendingSoItCanBeRetried() {
            request.releaseExpiredLease(RETRY_POLICY, afterLease);

            assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
            assertThat(request.getFailureReason()).contains("sem confirmação do provedor");
        }

        @Test
        void refusesToReleaseALeaseThatIsStillValid() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> request.releaseExpiredLease(RETRY_POLICY, LATER.plusSeconds(1)));
        }
    }

    @Nested
    class InvalidTransitions {

        @Test
        void cannotCompleteARequestThatWasNotSentToTheProvider() {
            ActivationRequest request = ActivationRequest.register("system-a", DETAILS, null, NOW);

            assertThatThrownBy(() -> request.applyOutcome(new Activated(), RETRY_POLICY, LATER))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("PENDING para COMPLETED");
        }

        @Test
        void completedRequestCannotGoBackToProcessing() {
            ActivationRequest request = processingRequest();
            request.applyOutcome(new Activated(), RETRY_POLICY, LATER);

            assertThatThrownBy(() -> request.startProcessing(LEASE, LATER))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("COMPLETED para PROCESSING");
        }

        @Test
        void failedRequestCannotBeRetried() {
            ActivationRequest request = processingRequest();
            request.applyOutcome(new Rejected("dados inválidos"), RETRY_POLICY, LATER);

            assertThatThrownBy(() -> request.applyOutcome(TemporaryFailure.of("HTTP 503"), RETRY_POLICY, LATER))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }
    }

    private static ActivationRequest processingRequest() {
        ActivationRequest request = ActivationRequest.register("system-a", DETAILS, null, NOW);
        request.startProcessing(LEASE, LATER);
        return request;
    }

    private static void exhaustAttempts(ActivationRequest request) {
        request.applyOutcome(TemporaryFailure.of("HTTP 500"), RETRY_POLICY, LATER);
        request.startProcessing(LEASE, LATER);
        request.applyOutcome(TemporaryFailure.of("HTTP 500"), RETRY_POLICY, LATER);
        request.startProcessing(LEASE, LATER);
    }
}
