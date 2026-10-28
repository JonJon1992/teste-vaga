package dev.challenge.activation.domain;

public interface ActivationProvider {

    ActivationOutcome activate(ActivationRequest request);
}
