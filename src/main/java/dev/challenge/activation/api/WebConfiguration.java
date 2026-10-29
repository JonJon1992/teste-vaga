package dev.challenge.activation.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
class WebConfiguration implements WebMvcConfigurer {

    private final int requestsPerMinute;

    WebConfiguration(@Value("${activation.api.rate-limit.requests-per-minute:120}") int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ClientRateLimitInterceptor(requestsPerMinute)).addPathPatterns("/api/**");
    }
}
