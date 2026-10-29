package dev.challenge.activation.api;

import dev.challenge.activation.application.ActivationRequestNotFoundException;
import dev.challenge.activation.application.ActivationRequestQueries;
import dev.challenge.activation.application.CreateActivationRequestCommand;
import dev.challenge.activation.application.CreateActivationRequestUseCase;
import dev.challenge.activation.application.CreationResult.AlreadyExists;
import dev.challenge.activation.application.CreationResult.Created;
import dev.challenge.activation.application.ExternalIdConflictException;
import dev.challenge.activation.domain.activation.ActivationDetails;
import dev.challenge.activation.domain.activation.ActivationRequest;
import dev.challenge.activation.domain.activation.ActivationRequestFilter;
import dev.challenge.activation.domain.activation.ActivationStatus;
import dev.challenge.activation.domain.shared.PageQuery;
import dev.challenge.activation.domain.shared.ResultPage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
class ActivationRequestControllerTest {

    private static final RequestPostProcessor READER = client("system-a", SecurityConfiguration.READ_SCOPE);
    private static final RequestPostProcessor WRITER = client("system-a", SecurityConfiguration.WRITE_SCOPE);
    private static final Instant CREATED_AT = Instant.parse("2026-10-26T14:03:11Z");
    private static final ActivationRequest REQUEST = ActivationRequest.register("system-a",
            new ActivationDetails("EXT-12345", "CUST-10001", "INTERNET-500"), null, CREATED_AT);
    private static final String VALID_BODY = """
            {"externalId": "EXT-12345", "customerId": "CUST-10001", "productCode": "INTERNET-500"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateActivationRequestUseCase createActivationRequest;

    @MockitoBean
    private ActivationRequestQueries queries;

    @Nested
    class Create {

        @Test
        void returns201WithLocationForANewRequest() throws Exception {
            when(createActivationRequest.create(any())).thenReturn(new Created(REQUEST));

            mockMvc.perform(postRequest(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", endsWith("/api/v1/requests/" + REQUEST.getId())))
                    .andExpect(jsonPath("$.id").value(REQUEST.getId().toString()))
                    .andExpect(jsonPath("$.externalId").value("EXT-12345"))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.createdAt").value("2026-10-26T14:03:11Z"))
                    .andExpect(jsonPath("$.failureReason").doesNotExist());
        }

        @Test
        void passesTheAuthenticatedClientAndCorrelationIdToTheUseCase() throws Exception {
            when(createActivationRequest.create(any())).thenReturn(new Created(REQUEST));
            ArgumentCaptor<CreateActivationRequestCommand> command =
                    ArgumentCaptor.forClass(CreateActivationRequestCommand.class);

            mockMvc.perform(postRequest(VALID_BODY).header(CorrelationIdFilter.HEADER, "corr-12345678"))
                    .andExpect(header().string(CorrelationIdFilter.HEADER, "corr-12345678"));

            verify(createActivationRequest).create(command.capture());
            assertThat(command.getValue().clientId()).isEqualTo("system-a");
            assertThat(command.getValue().correlationId()).isEqualTo("corr-12345678");
            assertThat(command.getValue().details().productCode()).isEqualTo("INTERNET-500");
        }

        @Test
        void returns200WithTheExistingRequestOnALegitimateRetry() throws Exception {
            when(createActivationRequest.create(any())).thenReturn(new AlreadyExists(REQUEST));

            mockMvc.perform(postRequest(VALID_BODY))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.id").value(REQUEST.getId().toString()));
        }

        @Test
        void returns409WhenTheExternalIdWasUsedWithDifferentDetails() throws Exception {
            when(createActivationRequest.create(any())).thenThrow(new ExternalIdConflictException("EXT-12345"));

            mockMvc.perform(postRequest(VALID_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/external-id-conflict"))
                    .andExpect(jsonPath("$.detail", containsString("EXT-12345")));
        }

        @Test
        void returns400ListingEveryInvalidField() throws Exception {
            String body = """
                    {"externalId": "%s", "customerId": "", "productCode": "INTERNET 500"}
                    """.formatted("X".repeat(65));

            mockMvc.perform(postRequest(body).header(CorrelationIdFilter.HEADER, "trace-12345678"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/validation-error"))
                    .andExpect(jsonPath("$.title").value("Dados inválidos"))
                    .andExpect(jsonPath("$.instance").value("/api/v1/requests"))
                    .andExpect(jsonPath("$.traceId").value("trace-12345678"))
                    .andExpect(jsonPath("$.errors", hasSize(3)))
                    .andExpect(jsonPath("$.errors[?(@.field == 'customerId')].message").value("é obrigatório"))
                    .andExpect(jsonPath("$.errors[?(@.field == 'externalId')].message")
                            .value("deve ter no máximo 64 caracteres"))
                    .andExpect(jsonPath("$.errors[?(@.field == 'productCode')].message")
                            .value("deve conter apenas letras, números, '.', '_' ou '-'"));
        }

        @Test
        void returns400WhenTheBodyIsNotValidJson() throws Exception {
            mockMvc.perform(postRequest("{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/malformed-request"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }

        @Test
        void returns500WithoutLeakingInternalDetails() throws Exception {
            when(createActivationRequest.create(any()))
                    .thenThrow(new IllegalStateException("password=s3cr3t at org.postgresql.Driver"));

            mockMvc.perform(postRequest(VALID_BODY))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/internal-error"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty())
                    .andExpect(content().string(not(containsString("s3cr3t"))))
                    .andExpect(content().string(not(containsString("postgresql"))));
        }
    }

    @Nested
    class FindById {

        @Test
        void returnsTheRequestWithItsCurrentStatus() throws Exception {
            when(queries.findById(REQUEST.getId(), "system-a")).thenReturn(REQUEST);

            mockMvc.perform(get("/api/v1/requests/{id}", REQUEST.getId()).with(READER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(REQUEST.getId().toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        void returns404WhenTheRequestDoesNotExist() throws Exception {
            UUID unknownId = UUID.randomUUID();
            when(queries.findById(unknownId, "system-a")).thenThrow(new ActivationRequestNotFoundException(unknownId));

            mockMvc.perform(get("/api/v1/requests/{id}", unknownId).with(READER))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/not-found"))
                    .andExpect(jsonPath("$.instance").value("/api/v1/requests/" + unknownId));
        }

        @Test
        void returns400WhenTheIdIsNotAUuid() throws Exception {
            mockMvc.perform(get("/api/v1/requests/{id}", "not-a-uuid").with(READER))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("id"));
        }
    }

    @Nested
    class Search {

        @Test
        void filtersByTheAuthenticatedClientAndTheGivenParameters() throws Exception {
            when(queries.search(any(), any())).thenReturn(new ResultPage<>(List.of(REQUEST), 1, 10, 11));

            mockMvc.perform(get("/api/v1/requests")
                            .param("status", "PENDING")
                            .param("customerId", "CUST-10001")
                            .param("page", "1")
                            .param("size", "10")
                            .with(READER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(REQUEST.getId().toString()))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.totalElements").value(11))
                    .andExpect(jsonPath("$.totalPages").value(2));

            verify(queries).search(
                    eq(new ActivationRequestFilter("system-a", ActivationStatus.PENDING, "CUST-10001")),
                    eq(new PageQuery(1, 10)));
        }

        @Test
        void returns400WhenThePageSizeIsTooLarge() throws Exception {
            mockMvc.perform(get("/api/v1/requests").param("size", "500").with(READER))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("size"))
                    .andExpect(jsonPath("$.errors[0].message").value("deve ser no máximo 100"));
        }

        @Test
        void returns400ForAnUnknownStatus() throws Exception {
            mockMvc.perform(get("/api/v1/requests").param("status", "DONE").with(READER))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("status"));
        }
    }

    private static MockHttpServletRequestBuilder postRequest(String body) {
        return post("/api/v1/requests")
                .with(WRITER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static RequestPostProcessor client(String clientId, String scope) {
        return jwt().jwt(token -> token.subject(clientId)).authorities(new SimpleGrantedAuthority(scope));
    }
}
