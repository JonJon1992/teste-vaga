package dev.challenge.activation.domain;

public class DuplicateActivationRequestException extends RuntimeException {

    public DuplicateActivationRequestException(String externalId, Throwable cause) {
        super("Já existe uma solicitação com externalId " + externalId, cause);
    }
}
