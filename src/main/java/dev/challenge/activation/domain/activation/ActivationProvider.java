package dev.challenge.activation.domain.activation;

public interface ActivationProvider {

    ActivationOutcome activate(ActivationRequest request);
}
