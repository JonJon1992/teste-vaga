package dev.challenge.activation.domain.activation;

public class DuplicateActivationRequestException extends RuntimeException {

    public DuplicateActivationRequestException(String externalId, Throwable cause) {
        super("Já existe uma solicitação com externalId " + externalId, cause);
    }
}
