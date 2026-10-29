package dev.challenge.activation.api;

import dev.challenge.activation.domain.activation.ActivationDetails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateActivationRequestBody(
        @NotBlank(message = REQUIRED)
        @Size(max = MAX_LENGTH, message = TOO_LONG)
        @Pattern(regexp = IDENTIFIER, message = INVALID_FORMAT)
        String externalId,

        @NotBlank(message = REQUIRED)
        @Size(max = MAX_LENGTH, message = TOO_LONG)
        @Pattern(regexp = IDENTIFIER, message = INVALID_FORMAT)
        String customerId,

        @NotBlank(message = REQUIRED)
        @Size(max = MAX_LENGTH, message = TOO_LONG)
        @Pattern(regexp = IDENTIFIER, message = INVALID_FORMAT)
        String productCode) {

    private static final int MAX_LENGTH = 64;
    private static final String IDENTIFIER = "^[A-Za-z0-9._-]*$";
    private static final String REQUIRED = "é obrigatório";
    private static final String TOO_LONG = "deve ter no máximo {max} caracteres";
    private static final String INVALID_FORMAT = "deve conter apenas letras, números, '.', '_' ou '-'";

    ActivationDetails toDetails() {
        return new ActivationDetails(externalId, customerId, productCode);
    }
}
