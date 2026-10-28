package dev.challenge.activation.infrastructure.persistence;

import dev.challenge.activation.TestcontainersConfiguration;
import dev.challenge.activation.domain.activation.ActivationDetails;
import dev.challenge.activation.domain.activation.ActivationRequest;
import dev.challenge.activation.domain.activation.ActivationRequestFilter;
import dev.challenge.activation.domain.activation.ActivationStatus;
import dev.challenge.activation.domain.activation.DuplicateActivationRequestException;
import dev.challenge.activation.domain.shared.PageQuery;
import dev.challenge.activation.domain.shared.ResultPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaActivationRequestRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JpaActivationRequestRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(1);

    @Autowired
    private JpaActivationRequestRepository repository;

    @Autowired
    private SpringDataActivationRequestRepository jpaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        jpaRepository.deleteAllInBatch();
    }

    @Nested
    class Uniqueness {

        @Test
        void findsByExternalIdWithinTheSameClient() {
            ActivationRequest saved = repository.add(request("system-a", "EXT-1", NOW));

            assertThat(repository.findByExternalId("system-a", "EXT-1")).contains(saved);
            assertThat(repository.findByExternalId("system-b", "EXT-1")).isEmpty();
        }

        @Test
        void rejectsASecondRequestWithTheSameExternalIdForTheSameClient() {
            repository.add(request("system-a", "EXT-1", NOW));

            assertThatThrownBy(() -> repository.add(request("system-a", "EXT-1", NOW)))
                    .isInstanceOf(DuplicateActivationRequestException.class);
            assertThat(jpaRepository.count()).isEqualTo(1);
        }

        @Test
        void acceptsTheSameExternalIdFromDifferentClients() {
            repository.add(request("system-a", "EXT-1", NOW));
            repository.add(request("system-b", "EXT-1", NOW));

            assertThat(jpaRepository.count()).isEqualTo(2);
        }
    }

    @Test
    void rejectsConcurrentUpdateWithAStaleVersion() {
        ActivationRequest saved = repository.add(request("system-a", "EXT-1", NOW));
        ActivationRequest firstCopy = repository.findById(saved.getId()).orElseThrow();
        ActivationRequest staleCopy = repository.findById(saved.getId()).orElseThrow();

        firstCopy.startProcessing(LEASE, NOW);
        repository.save(firstCopy);
        staleCopy.startProcessing(LEASE, NOW);

        assertThatThrownBy(() -> repository.save(staleCopy)).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void searchesByClientWithOptionalFiltersNewestFirst() {
        repository.add(request("system-a", "EXT-1", "CUST-1", NOW));
        repository.add(request("system-a", "EXT-2", "CUST-1", NOW.plusSeconds(1)));
        repository.add(request("system-a", "EXT-3", "CUST-2", NOW.plusSeconds(2)));
        repository.add(request("system-b", "EXT-4", "CUST-1", NOW.plusSeconds(3)));

        ResultPage<ActivationRequest> page = repository.search(
                new ActivationRequestFilter("system-a", ActivationStatus.PENDING, "CUST-1"), new PageQuery(0, 1));

        assertThat(page.items()).extracting(ActivationRequest::getExternalId).containsExactly("EXT-2");
        assertThat(page.totalItems()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Nested
    class Locking {

        @Test
        void locksOnlyPendingRequestsThatAreDueOldestFirst() {
            ActivationRequest oldest = repository.add(request("system-a", "EXT-1", NOW.minusSeconds(10)));
            ActivationRequest due = repository.add(request("system-a", "EXT-2", NOW));
            repository.add(request("system-a", "EXT-3", NOW.plusSeconds(10)));

            List<ActivationRequest> locked = inTransaction(() -> repository.lockDueForDispatch(NOW, 10));

            assertThat(locked).containsExactly(oldest, due);
        }

        @Test
        void skipsRequestsAlreadyLockedByAnotherTransaction() {
            ActivationRequest first = repository.add(request("system-a", "EXT-1", NOW.minusSeconds(10)));
            ActivationRequest second = repository.add(request("system-a", "EXT-2", NOW));

            List<ActivationRequest> lockedBySecondWorker = inTransaction(() -> {
                assertThat(repository.lockDueForDispatch(NOW, 1)).containsExactly(first);
                return inNewTransaction(() -> repository.lockDueForDispatch(NOW, 10));
            });

            assertThat(lockedBySecondWorker).containsExactly(second);
        }

        @Test
        void locksProcessingRequestsWhoseLeaseExpired() {
            ActivationRequest expired = repository.add(request("system-a", "EXT-1", NOW));
            ActivationRequest active = repository.add(request("system-a", "EXT-2", NOW));
            expired.startProcessing(LEASE, NOW.minus(LEASE));
            active.startProcessing(LEASE, NOW);
            repository.save(expired);
            repository.save(active);

            List<ActivationRequest> locked = inTransaction(() -> repository.lockWithExpiredLease(NOW, 10));

            assertThat(locked).containsExactly(expired);
        }

        @Test
        void requiresAnOpenTransaction() {
            assertThatThrownBy(() -> repository.lockDueForDispatch(NOW, 10))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private <T> T inNewTransaction(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }

    private static ActivationRequest request(String clientId, String externalId, Instant now) {
        return request(clientId, externalId, "CUST-1", now);
    }

    private static ActivationRequest request(String clientId, String externalId, String customerId, Instant now) {
        return ActivationRequest.register(clientId, new ActivationDetails(externalId, customerId, "INTERNET-500"),
                null, now);
    }
}
