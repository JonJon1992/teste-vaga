package dev.challenge.activation.infrastructure.scheduling;

import dev.challenge.activation.application.DispatchSettings;
import dev.challenge.activation.domain.activation.RetryPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Random;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ProcessingProperties.class)
class ProcessingConfiguration {

    @Bean
    RetryPolicy retryPolicy(ProcessingProperties properties) {
        return new RetryPolicy(properties.maxAttempts(), properties.initialBackoff(), properties.maxBackoff(),
                new Random());
    }

    @Bean
    DispatchSettings dispatchSettings(ProcessingProperties properties) {
        return new DispatchSettings(properties.batchSize(), properties.lease());
    }
}
