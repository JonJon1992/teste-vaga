package dev.challenge.activation.domain.activation;

import dev.challenge.activation.domain.activation.ActivationOutcome.Activated;
import dev.challenge.activation.domain.activation.ActivationOutcome.Rejected;
import dev.challenge.activation.domain.activation.ActivationOutcome.TemporaryFailure;
import dev.challenge.activation.domain.shared.Guard;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "activation_request")
public class ActivationRequest {

    private static final int MAX_FAILURE_REASON_LENGTH = 500;
    private static final String INTERRUPTED_PROCESSING = "Processamento interrompido sem confirmação do provedor";

    @Id
    private UUID id;
    private String clientId;
    private String externalId;
    private String customerId;
    private String productCode;
    @Enumerated(EnumType.STRING)
    private ActivationStatus status;
    private int attempts;
    private Instant nextAttemptAt;
    private String failureReason;
    private String correlationId;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private Long version;

    protected ActivationRequest() {
    }

    private ActivationRequest(String clientId, ActivationDetails details, String correlationId, Instant now) {
        this.id = UUID.randomUUID();
        this.clientId = Guard.notBlank(clientId, "clientId");
        this.externalId = details.externalId();
        this.customerId = details.customerId();
        this.productCode = details.productCode();
        this.status = ActivationStatus.PENDING;
        this.nextAttemptAt = now;
        this.correlationId = correlationId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ActivationRequest register(String clientId, ActivationDetails details, String correlationId,
                                             Instant now) {
        Objects.requireNonNull(details, "details");
        Objects.requireNonNull(now, "now");
        return new ActivationRequest(clientId, details, correlationId, now);
    }

    public void startProcessing(Duration lease, Instant now) {
        transitionTo(ActivationStatus.PROCESSING, now);
        attempts++;
        nextAttemptAt = now.plus(lease);
    }

    public void applyOutcome(ActivationOutcome outcome, RetryPolicy retryPolicy, Instant now) {
        switch (outcome) {
            case Activated() -> complete(now);
            case Rejected(String reason) -> fail(reason, now);
            case TemporaryFailure(String reason, Duration retryAfter) -> retryOrFail(reason, retryAfter, retryPolicy, now);
        }
    }

    public void releaseExpiredLease(RetryPolicy retryPolicy, Instant now) {
        if (status != ActivationStatus.PROCESSING || now.isBefore(nextAttemptAt)) {
            throw new IllegalStateException("Solicitação %s não possui reserva expirada".formatted(id));
        }
        retryOrFail(INTERRUPTED_PROCESSING, null, retryPolicy, now);
    }

    public ActivationDetails details() {
        return new ActivationDetails(externalId, customerId, productCode);
    }

    public boolean hasSameDetailsAs(ActivationDetails details) {
        return details().equals(details);
    }

    public boolean isOwnedBy(String clientId) {
        return this.clientId.equals(clientId);
    }

    private void complete(Instant now) {
        transitionTo(ActivationStatus.COMPLETED, now);
        nextAttemptAt = null;
        failureReason = null;
    }

    private void fail(String reason, Instant now) {
        transitionTo(ActivationStatus.FAILED, now);
        nextAttemptAt = null;
        failureReason = abbreviate(reason);
    }

    private void retryOrFail(String reason, Duration retryAfter, RetryPolicy retryPolicy, Instant now) {
        if (!retryPolicy.allowsRetryAfter(attempts)) {
            fail("Limite de %d tentativas atingido. Último erro: %s".formatted(attempts, reason), now);
            return;
        }
        transitionTo(ActivationStatus.PENDING, now);
        nextAttemptAt = retryPolicy.nextAttemptAt(attempts, retryAfter, now);
        failureReason = abbreviate(reason);
    }

    private void transitionTo(ActivationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(id, status, target);
        }
        status = target;
        updatedAt = now;
    }

    private static String abbreviate(String reason) {
        if (reason == null || reason.length() <= MAX_FAILURE_REASON_LENGTH) {
            return reason;
        }
        return reason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public ActivationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ActivationRequest that && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
