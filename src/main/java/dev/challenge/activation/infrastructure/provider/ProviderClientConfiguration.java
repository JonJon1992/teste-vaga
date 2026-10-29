package dev.challenge.activation.infrastructure.provider;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
class ProviderClientConfiguration {

    @Bean
    RestClient providerRestClient(RestClient.Builder builder, ProviderProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> {
                    if (StringUtils.hasText(properties.apiKey())) {
                        headers.setBearerAuth(properties.apiKey());
                    }
                })
                .build();
    }
}
