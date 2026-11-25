package vn.edu.uit.nearby.nearby_backend.transaction.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.edu.uit.nearby.nearby_backend.support.IntegrationTest;

@IntegrationTest
class TransactionReservationIntegrationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Test
	void concurrentAcceptsAllowOnlyOneReservationAndCancellationReleasesIt() {
		UUID listing = UUID.randomUUID();
		UUID seller = UUID.randomUUID();
		TransactionTemplate transaction = new TransactionTemplate(this.transactionManager);
		transaction.setTimeout(30);

		try (var executor = Executors.newSingleThreadExecutor()) {
			UUID first = insertOffer(listing, seller);
			UUID second = insertOffer(listing, seller);
			var waitingPid = new CompletableFuture<Integer>();

			var competingAccept = transaction.execute(status -> {
				assertThat(accept(first)).isEqualTo(1);
				int blockingPid = backendPid();
				var competing = executor.submit(() -> transaction.execute(otherStatus -> {
					waitingPid.complete(backendPid());
					return accept(second);
				}));

				// Keep the first transaction open until PostgreSQL confirms the second is waiting.
				int otherPid = waitingPid.orTimeout(10, TimeUnit.SECONDS).join();
				await().pollInSameThread()
					.atMost(Duration.ofSeconds(10))
					.until(() -> isBlocked(blockingPid, otherPid));
				return competing;
			});

			assertThatThrownBy(() -> competingAccept.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(DuplicateKeyException.class)
				.hasStackTraceContaining("transactions_accepted_listing_uq");
			assertThat(statusOf(first)).isEqualTo("ACCEPTED");
			assertThat(statusOf(second)).isEqualTo("OFFERED");

			int cancelled = this.jdbc.update("UPDATE transactions SET status = 'CANCELLED' WHERE id = ?", first);
			assertThat(cancelled).isEqualTo(1);
			assertThat(accept(second)).isEqualTo(1);
			assertThat(statusOf(second)).isEqualTo("ACCEPTED");
		}
		finally {
			this.jdbc.update("DELETE FROM transactions WHERE listing_id = ?", listing);
		}
	}

	private UUID insertOffer(UUID listing, UUID seller) {
		return this.jdbc.queryForObject("""
				INSERT INTO transactions (id, listing_id, buyer_id, seller_id, offered_price)
				VALUES (gen_random_uuid(), ?, ?, ?, 100000)
				RETURNING id
				""", UUID.class, listing, UUID.randomUUID(), seller);
	}

	private int accept(UUID id) {
		return this.jdbc.update("UPDATE transactions SET status = 'ACCEPTED' WHERE id = ? AND status = 'OFFERED'", id);
	}

	private String statusOf(UUID id) {
		return this.jdbc.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class, id);
	}

	private int backendPid() {
		return this.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
	}

	private boolean isBlocked(int blockingPid, int waitingPid) {
		return Boolean.TRUE.equals(this.jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class,
				blockingPid, waitingPid));
	}

}
