package dev.challenge.activation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
class ActivationApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate apiPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    void startsAndAppliesDatabaseMigrations() {
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'activation_request'",
                Integer.class);

        assertThat(tables).isEqualTo(1);
    }

    @Test
    void exposesLivenessAndReadinessProbesOnTheManagementPort() {
        ResponseEntity<JsonNode> liveness = management("/actuator/health/liveness", JsonNode.class);
        ResponseEntity<JsonNode> readiness = management("/actuator/health/readiness", JsonNode.class);

        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveness.getBody().get("status").asText()).isEqualTo("UP");
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody().get("status").asText()).isEqualTo("UP");
    }

    @Test
    void exposesMetricsForPrometheusOnTheManagementPort() {
        ResponseEntity<String> metrics = management("/actuator/prometheus", String.class);

        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getBody())
                .contains("activation_requests{")
                .contains("status=\"PENDING\"")
                .contains("jvm_memory_used_bytes");
    }

    @Test
    void keepsOtherActuatorEndpointsClosed() {
        assertThat(management("/actuator/env", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void doesNotServeActuatorOnTheApiPort() {
        assertThat(apiPort.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private <T> ResponseEntity<T> management(String path, Class<T> type) {
        return apiPort.getRestTemplate().getForEntity("http://localhost:" + managementPort + path, type);
    }
}
