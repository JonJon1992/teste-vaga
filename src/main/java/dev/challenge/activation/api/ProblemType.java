package dev.challenge.activation.api;

import java.net.URI;

enum ProblemType {
    VALIDATION_ERROR("validation-error", "Dados inválidos"),
    MALFORMED_REQUEST("malformed-request", "Requisição malformada"),
    UNAUTHORIZED("unauthorized", "Não autenticado"),
    FORBIDDEN("forbidden", "Acesso negado"),
    NOT_FOUND("not-found", "Recurso não encontrado"),
    EXTERNAL_ID_CONFLICT("external-id-conflict", "externalId já utilizado"),
    SERVICE_UNAVAILABLE("service-unavailable", "Serviço indisponível"),
    INTERNAL_ERROR("internal-error", "Erro interno");

    private static final String BASE_URI = "https://api.exemplo.com/problems/";

    private final URI uri;
    private final String title;

    ProblemType(String slug, String title) {
        this.uri = URI.create(BASE_URI + slug);
        this.title = title;
    }

    URI uri() {
        return uri;
    }

    String title() {
        return title;
    }
}
