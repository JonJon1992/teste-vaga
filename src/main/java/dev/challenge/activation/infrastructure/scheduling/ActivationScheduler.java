package dev.challenge.activation.infrastructure.scheduling;

import dev.challenge.activation.application.ActivationDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "activation.processing", name = "enabled", havingValue = "true", matchIfMissing = true)
class ActivationScheduler {

    private final ActivationDispatcher dispatcher;

    ActivationScheduler(ActivationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${activation.processing.poll-interval}")
    void dispatchDueRequests() {
        dispatcher.dispatchDueRequests();
    }

    @Scheduled(fixedDelayString = "${activation.processing.recovery-interval}")
    void recoverExpiredLeases() {
        dispatcher.recoverExpiredLeases();
    }
}
