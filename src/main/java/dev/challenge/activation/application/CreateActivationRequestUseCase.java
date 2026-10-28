package dev.challenge.activation.application;

import dev.challenge.activation.application.CreationResult.AlreadyExists;
import dev.challenge.activation.application.CreationResult.Created;
import dev.challenge.activation.domain.activation.ActivationDetails;
import dev.challenge.activation.domain.activation.ActivationRequest;
import dev.challenge.activation.domain.activation.ActivationRequestRepository;
import dev.challenge.activation.domain.activation.DuplicateActivationRequestException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

@Service
public class CreateActivationRequestUseCase {

    private final ActivationRequestRepository repository;
    private final Clock clock;

    public CreateActivationRequestUseCase(ActivationRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public CreationResult create(CreateActivationRequestCommand command) {
        return findExisting(command).orElseGet(() -> register(command));
    }

    private CreationResult register(CreateActivationRequestCommand command) {
        ActivationRequest request = ActivationRequest.register(
                command.clientId(), command.details(), command.correlationId(), clock.instant());
        try {
            return new Created(repository.add(request));
        } catch (DuplicateActivationRequestException concurrentInsert) {
            return findExisting(command).orElseThrow(() -> concurrentInsert);
        }
    }

    private Optional<CreationResult> findExisting(CreateActivationRequestCommand command) {
        return repository.findByExternalId(command.clientId(), command.details().externalId())
                .map(existing -> replay(existing, command.details()));
    }

    private static CreationResult replay(ActivationRequest existing, ActivationDetails details) {
        if (!existing.hasSameDetailsAs(details)) {
            throw new ExternalIdConflictException(existing.getExternalId());
        }
        return new AlreadyExists(existing);
    }
}
