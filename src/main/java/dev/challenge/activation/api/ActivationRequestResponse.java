package dev.challenge.activation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.challenge.activation.domain.activation.ActivationRequest;
import dev.challenge.activation.domain.activation.ActivationStatus;

import java.time.Instant;
import java.util.UUID;

public record ActivationRequestResponse(
        UUID id,
        String externalId,
        String customerId,
        String productCode,
        ActivationStatus status,
        Instant createdAt,
        Instant updatedAt,
        int attempts,
        @JsonInclude(JsonInclude.Include.NON_NULL) String failureReason) {

    static ActivationRequestResponse from(ActivationRequest request) {
        return new ActivationRequestResponse(
                request.getId(),
                request.getExternalId(),
                request.getCustomerId(),
                request.getProductCode(),
                request.getStatus(),
                request.getCreatedAt(),
                request.getUpdatedAt(),
                request.getAttempts(),
                request.getFailureReason());
    }
}
