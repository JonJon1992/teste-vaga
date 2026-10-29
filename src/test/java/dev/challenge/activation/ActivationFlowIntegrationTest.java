package dev.challenge.activation;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "activation.processing.poll-interval=100ms",
        "activation.processing.initial-backoff=100ms",
        "activation.processing.max-backoff=200ms",
        "activation.provider.read-timeout=500ms",
        "activation.api.rate-limit.requests-per-minute=100000"
})
@Import(TestcontainersConfiguration.class)
class ActivationFlowIntegrationTest {

    @RegisterExtension
    static final WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String ACTIVATE_PATH = "/provider/v1/activate";
    private static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(10);

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("activation.provider.base-url", provider::baseUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void authenticateAsSourceSystem() {
        when(jwtDecoder.decode(anyString())).thenReturn(Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("system-it")
                .claim("scope", "activation:read activation:write")
                .build());
    }

    @Test
    void concurrentRequestsWithTheSameExternalIdCreateExactlyOneRecord() throws Exception {
        String externalId = "EXT-" + UUID.randomUUID();
        int parallelRequests = 10;
        CountDownLatch start = new CountDownLatch(1);

        List<ResponseEntity<JsonNode>> responses;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ResponseEntity<JsonNode>>> futures = IntStream.range(0, parallelRequests)
                    .mapToObj(i -> executor.submit(() -> {
                        start.await();
                        return create(externalId, "CUST-CONCURRENT", "corr-concurrent");
                    }))
                    .toList();
            start.countDown();
            responses = futures.stream().map(ActivationFlowIntegrationTest::resultOf).toList();
        }

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsOnly(HttpStatus.CREATED, HttpStatus.OK)
                .filteredOn(HttpStatus.CREATED::equals).hasSize(1);
        assertThat(responses).extracting(response -> response.getBody().get("id").asText()).containsOnly(
                responses.getFirst().getBody().get("id").asText());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from activation_request where external_id = ?", Integer.class, externalId))
                .isEqualTo(1);
    }

    @Test
    void activatesARequestPropagatingIdempotencyKeyAndCorrelationId() {
        String customerId = uniqueCustomer();
        stubProviderFor(customerId, ok());

        String id = createdId(create("EXT-" + UUID.randomUUID(), customerId, "corr-e2e-0001"));

        awaitStatus(id, "COMPLETED");
        provider.verify(postRequestedFor(urlEqualTo(ACTIVATE_PATH))
                .withHeader("Idempotency-Key", equalTo(id))
                .withHeader("X-Correlation-Id", equalTo("corr-e2e-0001")));
    }

    @Test
    void retriesAfterATemporaryFailureAndCompletes() {
        String customerId = uniqueCustomer();
        stubProviderInSequence(customerId, status(503).withHeader("Retry-After", "0"), ok());

        String id = createdId(create("EXT-" + UUID.randomUUID(), customerId, "corr-retry-0001"));

        JsonNode completed = awaitStatus(id, "COMPLETED");
        assertThat(completed.get("attempts").asInt()).isEqualTo(2);
    }

    @Test
    void retriesWithTheSameIdempotencyKeyWhenTheProviderTimesOut() {
        String customerId = uniqueCustomer();
        stubProviderInSequence(customerId, ok().withFixedDelay(1_500), ok());

        String id = createdId(create("EXT-" + UUID.randomUUID(), customerId, "corr-timeout-01"));

        JsonNode completed = awaitStatus(id, "COMPLETED");
        assertThat(completed.get("attempts").asInt()).isEqualTo(2);
        provider.verify(2, postRequestedFor(urlEqualTo(ACTIVATE_PATH)).withHeader("Idempotency-Key", equalTo(id)));
    }

    @Test
    void failsWithoutRetryWhenTheProviderRejectsTheRequest() {
        String customerId = uniqueCustomer();
        stubProviderFor(customerId, status(422));

        String id = createdId(create("EXT-" + UUID.randomUUID(), customerId, "corr-reject-01"));

        JsonNode failed = awaitStatus(id, "FAILED");
        assertThat(failed.get("attempts").asInt()).isEqualTo(1);
        assertThat(failed.get("failureReason").asText()).isEqualTo("Provedor rejeitou a ativação (HTTP 422)");
    }

    private ResponseEntity<JsonNode> create(String externalId, String customerId, String correlationId) {
        String body = """
                {"externalId": "%s", "customerId": "%s", "productCode": "INTERNET-500"}
                """.formatted(externalId, customerId);
        return restTemplate.exchange(RequestEntity.post("/api/v1/requests")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .header("X-Correlation-Id", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body), JsonNode.class);
    }

    private JsonNode awaitStatus(String id, String expectedStatus) {
        return await().atMost(PROCESSING_TIMEOUT).until(() -> find(id), request ->
                expectedStatus.equals(request.get("status").asText()));
    }

    private JsonNode find(String id) {
        return restTemplate.exchange(RequestEntity.get("/api/v1/requests/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .build(), JsonNode.class).getBody();
    }

    private static String createdId(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asText();
    }

    private static void stubProviderFor(String customerId,
                                        ResponseDefinitionBuilder response) {
        provider.stubFor(post(ACTIVATE_PATH)
                .withRequestBody(matchingJsonPath("$.customerId", equalTo(customerId)))
                .willReturn(response));
    }

    private static void stubProviderInSequence(String customerId,
                                               ResponseDefinitionBuilder first,
                                               ResponseDefinitionBuilder then) {
        provider.stubFor(post(ACTIVATE_PATH)
                .withRequestBody(matchingJsonPath("$.customerId", equalTo(customerId)))
                .inScenario(customerId).whenScenarioStateIs(STARTED)
                .willReturn(first)
                .willSetStateTo("second-call"));
        provider.stubFor(post(ACTIVATE_PATH)
                .withRequestBody(matchingJsonPath("$.customerId", equalTo(customerId)))
                .inScenario(customerId).whenScenarioStateIs("second-call")
                .willReturn(then));
    }

    private static String uniqueCustomer() {
        return "CUST-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static <T> T resultOf(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
