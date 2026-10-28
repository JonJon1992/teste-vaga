package dev.challenge.activation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository {

    ActivationRequest add(ActivationRequest request);

    ActivationRequest save(ActivationRequest request);

    Optional<ActivationRequest> findById(UUID id);

    Optional<ActivationRequest> findByExternalId(String clientId, String externalId);

    ResultPage<ActivationRequest> search(ActivationRequestFilter filter, PageQuery pageQuery);

    List<ActivationRequest> lockDueForDispatch(Instant now, int limit);

    List<ActivationRequest> lockWithExpiredLease(Instant now, int limit);
}
