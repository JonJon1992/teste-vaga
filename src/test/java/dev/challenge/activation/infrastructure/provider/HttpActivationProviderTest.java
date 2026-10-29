package dev.challenge.activation.infrastructure.provider;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.challenge.activation.domain.activation.ActivationDetails;
import dev.challenge.activation.domain.activation.ActivationOutcome;
import dev.challenge.activation.domain.activation.ActivationOutcome.Activated;
import dev.challenge.activation.domain.activation.ActivationOutcome.Rejected;
import dev.challenge.activation.domain.activation.ActivationOutcome.TemporaryFailure;
import dev.challenge.activation.domain.activation.ActivationRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static dev.challenge.activation.infrastructure.provider.HttpActivationProvider.ACTIVATE_PATH;
import static org.assertj.core.api.Assertions.assertThat;

class HttpActivationProviderTest {

    @RegisterExtension
    static final WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);

    private final ActivationRequest request = ActivationRequest.register("system-a",
            new ActivationDetails("EXT-1", "CUST-1", "INTERNET-500"), "corr-12345678", NOW);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private HttpActivationProvider activationProvider;

    @BeforeEach
    void setUp() {
        ProviderProperties properties = new ProviderProperties(
                URI.create(provider.baseUrl()), Duration.ofSeconds(1), READ_TIMEOUT, "provider-token");
        RestClient restClient = new ProviderClientConfiguration().providerRestClient(RestClient.builder(), properties);
        activationProvider = new HttpActivationProvider(restClient, Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);
    }

    @Test
    void countsOutcomesByType() {
        provider.stubFor(post(ACTIVATE_PATH).willReturn(ok()));
        activationProvider.activate(request);
        provider.stubFor(post(ACTIVATE_PATH).willReturn(status(500)));
        activationProvider.activate(request);

        assertThat(outcomeCount("activated")).isEqualTo(1);
        assertThat(outcomeCount("temporary_failure")).isEqualTo(1);
    }

    @Test
    void sendsTheIdempotencyKeyCorrelationIdAndCredential() {
        provider.stubFor(post(ACTIVATE_PATH).willReturn(ok()));

        activationProvider.activate(request);

        provider.verify(postRequestedFor(urlEqualTo(ACTIVATE_PATH))
                .withHeader("Idempotency-Key", equalTo(request.getId().toString()))
                .withHeader("X-Correlation-Id", equalTo("corr-12345678"))
                .withHeader("Authorization", equalTo("Bearer provider-token"))
                .withRequestBody(equalToJson("""
                        {"requestId": "%s", "customerId": "CUST-1", "productCode": "INTERNET-500"}
                        """.formatted(request.getId()))));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202})
    void treatsSuccessAsActivated(int statusCode) {
        provider.stubFor(post(ACTIVATE_PATH).willReturn(status(statusCode)));

        assertThat(activationProvider.activate(request)).isInstanceOf(Activated.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 422})
    void treatsClientErrorsAsDefinitiveRejection(int statusCode) {
        provider.stubFor(post(ACTIVATE_PATH).willReturn(status(statusCode).withBody("{\"error\":\"invalid\"}")));

        ActivationOutcome outcome = activationProvider.activate(request);

        assertThat(outcome).isEqualTo(new Rejected("Provedor rejeitou a ativação (HTTP %d)".formatted(statusCode)));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 504, 408})
    void treatsServerErrorsAsTemporary(int statusCode) {
        provider.stubFor(post(ACTIVATE_PATH).willReturn(status(statusCode)));

        ActivationOutcome outcome = activationProvider.activate(request);

        assertThat(outcome).isEqualTo(TemporaryFailure.of("Erro no provedor (HTTP %d)".formatted(statusCode)));
    }

    @Test
    void honorsRetryAfterInSecondsOnTooManyRequests() {
        provider.stubFor(post(ACTIVATE_PATH).willReturn(status(429).withHeader("Retry-After", "30")));

        ActivationOutcome outcome = activationProvider.activate(request);

        assertThat(outcome).isEqualTo(new TemporaryFailure("Provedor indisponível (HTTP 429)", Duration.ofSeconds(30)));
    }

    @Test
    void honorsRetryAfterAsHttpDateOnServiceUnavailable() {
        String inTwoMinutes = DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.plusSeconds(120).atZone(ZoneOffset.UTC));
        provider.stubFor(post(ACTIVATE_PATH).willReturn(status(503).withHeader("Retry-After", inTwoMinutes)));

        ActivationOutcome outcome = activationProvider.activate(request);

        assertThat(outcome).isEqualTo(new TemporaryFailure("Provedor indisponível (HTTP 503)", Duration.ofMinutes(2)));
    }

    @Test
    void treatsAReadTimeoutAsTemporary() {
        provider.stubFor(post(ACTIVATE_PATH).willReturn(ok().withFixedDelay((int) READ_TIMEOUT.toMillis() * 2)));

        ActivationOutcome outcome = activationProvider.activate(request);

        assertThat(outcome).isEqualTo(TemporaryFailure.of("Tempo de resposta do provedor esgotado"));
    }

    @Test
    void treatsAConnectionFailureAsTemporary() {
        provider.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ActivationOutcome outcome = activationProvider.activate(request);

        assertThat(outcome).isEqualTo(TemporaryFailure.of("Falha de comunicação com o provedor"));
    }

    private double outcomeCount(String outcome) {
        return meterRegistry.counter(HttpActivationProvider.OUTCOME_METRIC, "outcome", outcome).count();
    }
}
