package dev.challenge.activation.application;

public class ExternalIdConflictException extends RuntimeException {

    public ExternalIdConflictException(String externalId) {
        super("O externalId %s já foi utilizado em uma solicitação com dados diferentes".formatted(externalId));
    }
}
