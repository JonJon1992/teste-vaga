package dev.challenge.activation.domain.activation;

import dev.challenge.activation.domain.shared.Guard;

public record ActivationDetails(String externalId, String customerId, String productCode) {

    public ActivationDetails {
        Guard.notBlank(externalId, "externalId");
        Guard.notBlank(customerId, "customerId");
        Guard.notBlank(productCode, "productCode");
    }
}
