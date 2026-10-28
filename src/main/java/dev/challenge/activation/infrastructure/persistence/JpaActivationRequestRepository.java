package dev.challenge.activation.infrastructure.persistence;

import dev.challenge.activation.domain.activation.ActivationRequest;
import dev.challenge.activation.domain.activation.ActivationRequestFilter;
import dev.challenge.activation.domain.activation.ActivationRequestRepository;
import dev.challenge.activation.domain.activation.DuplicateActivationRequestException;
import dev.challenge.activation.domain.shared.PageQuery;
import dev.challenge.activation.domain.shared.ResultPage;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaActivationRequestRepository implements ActivationRequestRepository {

    static final String UNIQUE_EXTERNAL_ID_CONSTRAINT = "uk_activation_request_client_external_id";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    private final SpringDataActivationRequestRepository jpaRepository;

    JpaActivationRequestRepository(SpringDataActivationRequestRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public ActivationRequest add(ActivationRequest request) {
        try {
            return jpaRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            if (violatesUniqueExternalId(e)) {
                throw new DuplicateActivationRequestException(request.getExternalId(), e);
            }
            throw e;
        }
    }

    @Override
    public ActivationRequest save(ActivationRequest request) {
        return jpaRepository.save(request);
    }

    @Override
    public Optional<ActivationRequest> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<ActivationRequest> findByExternalId(String clientId, String externalId) {
        return jpaRepository.findByClientIdAndExternalId(clientId, externalId);
    }

    @Override
    public ResultPage<ActivationRequest> search(ActivationRequestFilter filter, PageQuery pageQuery) {
        PageRequest pageRequest = PageRequest.of(pageQuery.page(), pageQuery.size(), NEWEST_FIRST);
        Page<ActivationRequest> page = jpaRepository.findAll(matching(filter), pageRequest);
        return new ResultPage<>(page.getContent(), pageQuery.page(), pageQuery.size(), page.getTotalElements());
    }

    @Override
    public List<ActivationRequest> lockDueForDispatch(Instant now, int limit) {
        return jpaRepository.lockDueForDispatch(now, limit);
    }

    @Override
    public List<ActivationRequest> lockWithExpiredLease(Instant now, int limit) {
        return jpaRepository.lockWithExpiredLease(now, limit);
    }

    private static Specification<ActivationRequest> matching(ActivationRequestFilter filter) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("clientId"), filter.clientId()));
            if (filter.status() != null) {
                predicates.add(builder.equal(root.get("status"), filter.status()));
            }
            if (filter.customerId() != null) {
                predicates.add(builder.equal(root.get("customerId"), filter.customerId()));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static boolean violatesUniqueExternalId(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return UNIQUE_EXTERNAL_ID_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
