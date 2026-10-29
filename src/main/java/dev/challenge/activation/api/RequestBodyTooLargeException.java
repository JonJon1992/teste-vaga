package dev.challenge.activation.api;

import java.io.IOException;

class RequestBodyTooLargeException extends IOException {

    RequestBodyTooLargeException(long maxBytes) {
        super("O corpo da requisição excede o limite de %d bytes".formatted(maxBytes));
    }
}
