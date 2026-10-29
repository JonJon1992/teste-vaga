package dev.challenge.activation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.challenge.activation.application.ActivationRequestQueries;
import dev.challenge.activation.application.CreateActivationRequestUseCase;
import dev.challenge.activation.domain.shared.ResultPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

@WebMvcTest(value = ActivationRequestController.class, properties = {
        "activation.api.max-request-body-size=1KB",
        "activation.api.rate-limit.requests-per-minute=3"
})
@Import(SecurityConfiguration.class)
class RequestLimitsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateActivationRequestUseCase createActivationRequest;

    @MockitoBean
    private ActivationRequestQueries queries;

    @Test
    void rejectsABodyLargerThanTheLimitBeforeReachingTheController() throws Exception {
        String oversizedBody = """
                {"externalId": "%s", "customerId": "CUST-1", "productCode": "INTERNET-500"}
                """.formatted("X".repeat(2_000));

        mockMvc.perform(post("/api/v1/requests")
                        .with(client("big-body-client", SecurityConfiguration.WRITE_SCOPE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/payload-too-large"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        verifyNoInteractions(createActivationRequest);
    }

    @Test
    void stopsReadingABodyWithoutContentLengthOnceItExceedsTheLimit() {
        RequestBodyLimitFilter filter = new RequestBodyLimitFilter(DataSize.ofKilobytes(1), new ObjectMapper());
        MockHttpServletRequest chunkedRequest = new MockHttpServletRequest("POST", "/api/v1/requests") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        chunkedRequest.setContent("X".repeat(2_000).getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> filter.doFilter(chunkedRequest, new MockHttpServletResponse(),
                (request, response) -> request.getInputStream().readAllBytes()))
                .isInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void returns429WithRetryAfterWhenTheClientExceedsItsRate() throws Exception {
        when(queries.search(any(), any())).thenReturn(new ResultPage<>(List.of(), 0, 20, 0));
        RequestPostProcessor noisyClient = client("noisy-client", SecurityConfiguration.READ_SCOPE);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/requests").with(noisyClient)).andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/requests").with(noisyClient))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/too-many-requests"));
        mockMvc.perform(get("/api/v1/requests").with(client("quiet-client", SecurityConfiguration.READ_SCOPE)))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor client(String clientId, String scope) {
        return jwt().jwt(token -> token.subject(clientId)).authorities(new SimpleGrantedAuthority(scope));
    }
}
