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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.edu.uit.nearby.nearby_backend.support.IntegrationTest;

@IntegrationTest
class TransactionReservationIntegrationTests {

	@Autowired
	JdbcClient jdbc;

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
				await().pollInSameThread().atMost(Duration.ofSeconds(10)).until(() -> isBlocked(blockingPid, otherPid));
				return competing;
			});

			assertThatThrownBy(() -> competingAccept.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(DuplicateKeyException.class)
				.hasStackTraceContaining("transactions_accepted_listing_uq");
			assertThat(statusOf(first)).isEqualTo("ACCEPTED");
			assertThat(statusOf(second)).isEqualTo("OFFERED");

			int cancelled = this.jdbc.sql("UPDATE transactions SET status = 'CANCELLED' WHERE id = :id")
				.param("id", first)
				.update();
			assertThat(cancelled).isEqualTo(1);
			assertThat(accept(second)).isEqualTo(1);
			assertThat(statusOf(second)).isEqualTo("ACCEPTED");
		}
		finally {
			this.jdbc.sql("DELETE FROM transactions WHERE listing_id = :listing").param("listing", listing).update();
		}
	}

	private UUID insertOffer(UUID listing, UUID seller) {
		return this.jdbc.sql("""
				INSERT INTO transactions (id, listing_id, buyer_id, seller_id, offered_price)
				VALUES (gen_random_uuid(), :listing, :buyer, :seller, 100000)
				RETURNING id
				""").param("listing", listing).param("buyer", UUID.randomUUID()).param("seller", seller).query(UUID.class)
			.single();
	}

	private int accept(UUID id) {
		return this.jdbc.sql("UPDATE transactions SET status = 'ACCEPTED' WHERE id = :id AND status = 'OFFERED'")
			.param("id", id)
			.update();
	}

	private String statusOf(UUID id) {
		return this.jdbc.sql("SELECT status FROM transactions WHERE id = :id").param("id", id).query(String.class)
			.single();
	}

	private int backendPid() {
		return this.jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
	}

	private boolean isBlocked(int blockingPid, int waitingPid) {
		return this.jdbc.sql("SELECT :blockingPid = ANY(pg_blocking_pids(:waitingPid))")
			.param("blockingPid", blockingPid)
			.param("waitingPid", waitingPid)
			.query(Boolean.class)
			.single();
	}

}
