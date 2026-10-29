package dev.challenge.activation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String WRITE_SCOPE = "SCOPE_activation:write";
    static final String READ_SCOPE = "SCOPE_activation:read";

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        SecurityProblemHandler problemHandler = new SecurityProblemHandler(objectMapper);
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/error", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority(WRITE_SCOPE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/*")
                        .hasAuthority(READ_SCOPE)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
