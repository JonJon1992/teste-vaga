package dev.challenge.activation.infrastructure.persistence;

import dev.challenge.activation.domain.activation.ActivationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataActivationRequestRepository
        extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

    Optional<ActivationRequest> findByClientIdAndExternalId(String clientId, String externalId);

    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT * FROM activation_request
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ActivationRequest> lockDueForDispatch(@Param("now") Instant now, @Param("limit") int limit);

    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT * FROM activation_request
            WHERE status = 'PROCESSING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ActivationRequest> lockWithExpiredLease(@Param("now") Instant now, @Param("limit") int limit);
}
