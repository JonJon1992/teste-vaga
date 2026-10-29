package dev.challenge.activation.application;

import dev.challenge.activation.domain.activation.ActivationOutcome;
import dev.challenge.activation.domain.activation.ActivationOutcome.TemporaryFailure;
import dev.challenge.activation.domain.activation.ActivationProvider;
import dev.challenge.activation.domain.activation.ActivationRequest;
import dev.challenge.activation.domain.activation.ActivationRequestRepository;
import dev.challenge.activation.domain.activation.RetryPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ActivationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ActivationDispatcher.class);
    private static final String CORRELATION_ID_MDC_KEY = "traceId";
    static final String FINISHED_METRIC = "activation.requests.finished";

    private final ActivationRequestRepository repository;
    private final ActivationProvider provider;
    private final RetryPolicy retryPolicy;
    private final DispatchSettings settings;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public ActivationDispatcher(ActivationRequestRepository repository, ActivationProvider provider,
                                RetryPolicy retryPolicy, DispatchSettings settings,
                                TransactionOperations transactions, Clock clock, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.provider = provider;
        this.retryPolicy = retryPolicy;
        this.settings = settings;
        this.transactions = transactions;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public int dispatchDueRequests() {
        List<ActivationRequest> claimed = transactions.execute(status -> claimDueRequests());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            claimed.forEach(request -> executor.execute(() -> dispatch(request)));
        }
        return claimed.size();
    }

    public int recoverExpiredLeases() {
        List<ActivationRequest> released = transactions.execute(status -> releaseExpiredLeases());
        released.forEach(this::countIfFinished);
        return released.size();
    }

    private List<ActivationRequest> releaseExpiredLeases() {
        Instant now = clock.instant();
        List<ActivationRequest> expired = repository.lockWithExpiredLease(now, settings.batchSize());
        expired.forEach(request -> {
            request.releaseExpiredLease(retryPolicy, now);
            repository.save(request);
            log.warn("Solicitação {} ficou presa em PROCESSING e foi liberada como {}",
                    request.getId(), request.getStatus());
        });
        return expired;
    }

    private List<ActivationRequest> claimDueRequests() {
        Instant now = clock.instant();
        List<ActivationRequest> due = repository.lockDueForDispatch(now, settings.batchSize());
        due.forEach(request -> {
            request.startProcessing(settings.lease(), now);
            repository.save(request);
        });
        return due;
    }

    private void dispatch(ActivationRequest request) {
        if (request.getCorrelationId() != null) {
            MDC.put(CORRELATION_ID_MDC_KEY, request.getCorrelationId());
        }
        try {
            ActivationOutcome outcome = callProvider(request);
            transactions.executeWithoutResult(status -> applyOutcome(request, outcome));
            countIfFinished(request);
        } catch (OptimisticLockingFailureException concurrentUpdate) {
            log.warn("Solicitação {} foi atualizada por outro processo; resultado descartado", request.getId());
        } catch (RuntimeException failure) {
            log.error("Falha ao registrar o resultado da solicitação {}; será reprocessada após o lease",
                    request.getId(), failure);
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    private ActivationOutcome callProvider(ActivationRequest request) {
        try {
            return provider.activate(request);
        } catch (RuntimeException unexpected) {
            log.error("Erro inesperado ao chamar o provedor para a solicitação {}", request.getId(), unexpected);
            return TemporaryFailure.of("Erro inesperado ao chamar o provedor");
        }
    }

    private void applyOutcome(ActivationRequest request, ActivationOutcome outcome) {
        request.applyOutcome(outcome, retryPolicy, clock.instant());
        repository.save(request);
        log.info("Solicitação {} agora está {} após {} tentativa(s)",
                request.getId(), request.getStatus(), request.getAttempts());
    }

    private void countIfFinished(ActivationRequest request) {
        if (request.getStatus().isFinal()) {
            meterRegistry.counter(FINISHED_METRIC, "status", request.getStatus().name()).increment();
        }
    }
}
