package dev.challenge.activation.api;

import dev.challenge.activation.application.ActivationRequestQueries;
import dev.challenge.activation.application.CreateActivationRequestCommand;
import dev.challenge.activation.application.CreateActivationRequestUseCase;
import dev.challenge.activation.application.CreationResult;
import dev.challenge.activation.application.CreationResult.AlreadyExists;
import dev.challenge.activation.application.CreationResult.Created;
import dev.challenge.activation.domain.activation.ActivationRequestFilter;
import dev.challenge.activation.domain.activation.ActivationStatus;
import dev.challenge.activation.domain.shared.PageQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
class ActivationRequestController {

    private final CreateActivationRequestUseCase createActivationRequest;
    private final ActivationRequestQueries queries;

    ActivationRequestController(CreateActivationRequestUseCase createActivationRequest,
                                ActivationRequestQueries queries) {
        this.createActivationRequest = createActivationRequest;
        this.queries = queries;
    }

    @PostMapping
    ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequestBody body,
                                                     Principal client) {
        CreationResult result = createActivationRequest.create(new CreateActivationRequestCommand(
                client.getName(), body.toDetails(), CorrelationIdFilter.current()));
        ActivationRequestResponse response = ActivationRequestResponse.from(result.request());

        return switch (result) {
            case Created created -> ResponseEntity.created(locationOf(response.id())).body(response);
            case AlreadyExists alreadyExists -> ResponseEntity.ok(response);
        };
    }

    @GetMapping("/{id}")
    ActivationRequestResponse findById(@PathVariable UUID id, Principal client) {
        return ActivationRequestResponse.from(queries.findById(id, client.getName()));
    }

    @GetMapping
    PageResponse<ActivationRequestResponse> search(
            @RequestParam(required = false) ActivationStatus status,
            @RequestParam(required = false) String customerId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser no mínimo {value}") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "deve ser no mínimo {value}")
            @Max(value = PageQuery.MAX_SIZE, message = "deve ser no máximo {value}") int size,
            Principal client) {
        ActivationRequestFilter filter = new ActivationRequestFilter(client.getName(), status, customerId);
        return PageResponse.from(queries.search(filter, new PageQuery(page, size)), ActivationRequestResponse::from);
    }

    private static URI locationOf(UUID id) {
        return ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(id).toUri();
    }
}
