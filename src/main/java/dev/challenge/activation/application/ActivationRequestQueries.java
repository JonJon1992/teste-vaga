package dev.challenge.activation.application;

import dev.challenge.activation.domain.activation.ActivationRequest;
import dev.challenge.activation.domain.activation.ActivationRequestFilter;
import dev.challenge.activation.domain.activation.ActivationRequestRepository;
import dev.challenge.activation.domain.shared.PageQuery;
import dev.challenge.activation.domain.shared.ResultPage;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ActivationRequestQueries {

    private final ActivationRequestRepository repository;

    public ActivationRequestQueries(ActivationRequestRepository repository) {
        this.repository = repository;
    }

    public ActivationRequest findById(UUID id, String clientId) {
        return repository.findById(id)
                .filter(request -> request.isOwnedBy(clientId))
                .orElseThrow(() -> new ActivationRequestNotFoundException(id));
    }

    public ResultPage<ActivationRequest> search(ActivationRequestFilter filter, PageQuery pageQuery) {
        return repository.search(filter, pageQuery);
    }
}
