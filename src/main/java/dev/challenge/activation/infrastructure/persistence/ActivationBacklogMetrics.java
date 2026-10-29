package dev.challenge.activation.infrastructure.persistence;

import dev.challenge.activation.domain.activation.ActivationStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class ActivationBacklogMetrics implements MeterBinder {

    static final String METRIC_NAME = "activation.requests";
    private static final List<ActivationStatus> UNFINISHED = List.of(ActivationStatus.PENDING, ActivationStatus.PROCESSING);

    private final SpringDataActivationRequestRepository repository;

    ActivationBacklogMetrics(SpringDataActivationRequestRepository repository) {
        this.repository = repository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        UNFINISHED.forEach(status -> Gauge.builder(METRIC_NAME, repository, jpa -> jpa.countByStatus(status))
                .tag("status", status.name())
                .description("Solicitações ainda não finalizadas, por status")
                .strongReference(true)
                .register(registry));
    }
}
