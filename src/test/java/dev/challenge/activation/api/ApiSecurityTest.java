package dev.challenge.activation.api;

import dev.challenge.activation.application.ActivationRequestNotFoundException;
import dev.challenge.activation.application.ActivationRequestQueries;
import dev.challenge.activation.application.CreateActivationRequestUseCase;
import dev.challenge.activation.domain.shared.ResultPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivationRequestController.class)
@Import(SecurityConfiguration.class)
class ApiSecurityTest {

    private static final String VALID_BODY = """
            {"externalId": "EXT-1", "customerId": "CUST-1", "productCode": "INTERNET-500"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateActivationRequestUseCase createActivationRequest;

    @MockitoBean
    private ActivationRequestQueries queries;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void returns401WithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/unauthorized"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        verifyNoInteractions(queries);
    }

    @Test
    void returns401ForAnInvalidOrExpiredToken() throws Exception {
        when(jwtDecoder.decode("expired-token")).thenThrow(new BadJwtException("Jwt expired"));

        mockMvc.perform(get("/api/v1/requests").header(HttpHeaders.AUTHORIZATION, "Bearer expired-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
    }

    @Test
    void returns403WhenCreatingWithAReadOnlyToken() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .with(clientWithScope(SecurityConfiguration.READ_SCOPE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("insufficient_scope")))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/forbidden"));
        verifyNoInteractions(createActivationRequest);
    }

    @Test
    void returns403WhenReadingWithAWriteOnlyToken() throws Exception {
        mockMvc.perform(get("/api/v1/requests").with(clientWithScope(SecurityConfiguration.WRITE_SCOPE)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(queries);
    }

    @Test
    void mapsTheScopeClaimOfARealTokenToPermissions() throws Exception {
        when(jwtDecoder.decode("valid-token")).thenReturn(Jwt.withTokenValue("valid-token")
                .header("alg", "RS256")
                .subject("system-a")
                .claim("scope", "activation:read")
                .build());
        when(queries.search(any(), any())).thenReturn(new ResultPage<>(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/requests").header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isOk());
    }

    @Test
    void doesNotRevealRequestsOfAnotherClient() throws Exception {
        UUID requestOfSystemA = UUID.randomUUID();
        when(queries.findById(requestOfSystemA, "system-b"))
                .thenThrow(new ActivationRequestNotFoundException(requestOfSystemA));

        mockMvc.perform(get("/api/v1/requests/{id}", requestOfSystemA)
                        .with(jwt().jwt(token -> token.subject("system-b"))
                                .authorities(new SimpleGrantedAuthority(SecurityConfiguration.READ_SCOPE))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deniesEverythingOutsideTheDeclaredEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/admin").with(clientWithScope(SecurityConfiguration.READ_SCOPE)))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor clientWithScope(String scope) {
        return jwt().jwt(token -> token.subject("system-a")).authorities(new SimpleGrantedAuthority(scope));
    }
}
