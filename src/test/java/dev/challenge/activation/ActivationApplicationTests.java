package dev.challenge.activation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActivationApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startsAndAppliesDatabaseMigrations() {
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'activation_request'",
                Integer.class);

        assertThat(tables).isEqualTo(1);
    }
}
