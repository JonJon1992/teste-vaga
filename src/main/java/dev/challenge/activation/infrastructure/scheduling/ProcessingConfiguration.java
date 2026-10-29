package dev.challenge.activation.infrastructure.scheduling;

import dev.challenge.activation.application.DispatchSettings;
import dev.challenge.activation.domain.activation.RetryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
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
    DispatchSettings dispatchSettings(ProcessingProperties properties,
                                      @Value("${activation.provider.connect-timeout}") Duration connectTimeout,
                                      @Value("${activation.provider.read-timeout}") Duration readTimeout) {
        Duration longestProviderCall = connectTimeout.plus(readTimeout);
        if (properties.lease().compareTo(longestProviderCall) <= 0) {
            throw new IllegalStateException(("activation.processing.lease (%s) deve ser maior que a soma dos timeouts "
                    + "de conexão e leitura do provedor (%s); caso contrário uma chamada lenta seria reprocessada "
                    + "enquanto ainda está em andamento").formatted(properties.lease(), longestProviderCall));
        }
        return new DispatchSettings(properties.batchSize(), properties.lease());
    }
}
