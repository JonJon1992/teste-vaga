package dev.challenge.activation.infrastructure.provider;

import dev.challenge.activation.domain.activation.ActivationOutcome;
import dev.challenge.activation.domain.activation.ActivationOutcome.Activated;
import dev.challenge.activation.domain.activation.ActivationOutcome.Rejected;
import dev.challenge.activation.domain.activation.ActivationOutcome.TemporaryFailure;
import dev.challenge.activation.domain.activation.ActivationProvider;
import dev.challenge.activation.domain.activation.ActivationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Component
class HttpActivationProvider implements ActivationProvider {

    static final String ACTIVATE_PATH = "/provider/v1/activate";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private static final Logger log = LoggerFactory.getLogger(HttpActivationProvider.class);
    private static final int MAX_LOGGED_BODY_BYTES = 200;

    private final RestClient providerRestClient;
    private final Clock clock;

    HttpActivationProvider(RestClient providerRestClient, Clock clock) {
        this.providerRestClient = providerRestClient;
        this.clock = clock;
    }

    @Override
    public ActivationOutcome activate(ActivationRequest request) {
        try {
            return providerRestClient.post()
                    .uri(ACTIVATE_PATH)
                    .header(IDEMPOTENCY_KEY_HEADER, request.getId().toString())
                    .headers(headers -> addCorrelationId(headers, request.getCorrelationId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ActivationPayload(request.getId(), request.getCustomerId(), request.getProductCode()))
                    .exchange((httpRequest, response) -> toOutcome(request, response));
        } catch (ResourceAccessException failure) {
            log.warn("Falha de comunicação com o provedor para a solicitação {}", request.getId(), failure);
            return TemporaryFailure.of(isTimeout(failure)
                    ? "Tempo de resposta do provedor esgotado"
                    : "Falha de comunicação com o provedor");
        }
    }

    private ActivationOutcome toOutcome(ActivationRequest request, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        log.info("Provedor respondeu HTTP {} para a solicitação {}", status.value(), request.getId());

        if (status.is2xxSuccessful()) {
            return new Activated();
        }
        if (isThrottling(status)) {
            Duration retryAfter = RetryAfterHeader.parse(
                    response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), clock.instant());
            return new TemporaryFailure("Provedor indisponível (HTTP %d)".formatted(status.value()), retryAfter);
        }
        if (status.is5xxServerError() || status.isSameCodeAs(HttpStatus.REQUEST_TIMEOUT)) {
            return TemporaryFailure.of("Erro no provedor (HTTP %d)".formatted(status.value()));
        }
        log.warn("Provedor rejeitou a solicitação {}: {}", request.getId(), bodyExcerpt(response));
        return new Rejected("Provedor rejeitou a ativação (HTTP %d)".formatted(status.value()));
    }

    private static boolean isThrottling(HttpStatusCode status) {
        return status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS) || status.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static boolean isTimeout(ResourceAccessException failure) {
        return failure.getCause() instanceof HttpTimeoutException || failure.getCause() instanceof SocketTimeoutException;
    }

    private static void addCorrelationId(HttpHeaders headers, String correlationId) {
        if (correlationId != null) {
            headers.set(CORRELATION_ID_HEADER, correlationId);
        }
    }

    private static String bodyExcerpt(ClientHttpResponse response) throws IOException {
        return new String(response.getBody().readNBytes(MAX_LOGGED_BODY_BYTES), StandardCharsets.UTF_8);
    }

    record ActivationPayload(UUID requestId, String customerId, String productCode) {
    }
}
