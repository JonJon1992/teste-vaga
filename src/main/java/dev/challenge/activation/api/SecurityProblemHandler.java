package dev.challenge.activation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler bearerAccessDeniedHandler = new BearerTokenAccessDeniedHandler();
    private final ObjectMapper objectMapper;

    SecurityProblemHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException, ServletException {
        bearerEntryPoint.commence(request, response, exception);
        write(response, Problems.of(HttpStatus.UNAUTHORIZED, ProblemType.UNAUTHORIZED,
                "Token de acesso ausente, inválido ou expirado.", request));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException, ServletException {
        bearerAccessDeniedHandler.handle(request, response, exception);
        write(response, Problems.of(HttpStatus.FORBIDDEN, ProblemType.FORBIDDEN,
                "O token não possui o escopo necessário para esta operação.", request));
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
