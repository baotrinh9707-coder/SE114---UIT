package vn.edu.uit.nearby.nearby_backend.listing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.uit.nearby.nearby_backend.support.IntegrationTest;

@IntegrationTest
@Transactional
class ListingDemoIntegrationTests {

	private static final String SEED = "/db/demo/seed_demo.sql";

	private static final UUID SELLER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

	@Autowired
	JdbcTemplate jdbc;

	@Test
	@Sql({ SEED, SEED })
	void demoSeedCanBeLoadedTwiceOnTheMigratedSchema() {
		Integer accounts = this.jdbc.queryForObject("""
				SELECT count(*) FROM users
				WHERE email IN ('seller1@example.test', 'buyer1@example.test') AND status = 'ACTIVE'
				""", Integer.class);
		Integer listings = this.jdbc.queryForObject("""
				SELECT count(*) FROM listings WHERE seller_id = ?
				""", Integer.class, SELLER_ID);
		Integer listingsWithImages = this.jdbc.queryForObject("""
				SELECT count(*) FROM listings l
				JOIN categories c ON c.id = l.category_id
				JOIN listing_images i ON i.listing_id = l.id
				WHERE l.seller_id = ? AND c.code = 'ELECTRONICS' AND i.position = 1
				""", Integer.class, SELLER_ID);

		assertThat(accounts).isEqualTo(2);
		assertThat(listings).isEqualTo(20);
		assertThat(listingsWithImages).isEqualTo(20);
	}

	@Test
	@Sql(SEED)
	void radiusQueryFindsTheTenNearbyListingsUsingPublicLocation() {
		var titles = this.jdbc.queryForList("""
				SELECT title FROM listings
				WHERE seller_id = ? AND status = 'ACTIVE'
				  AND ST_DWithin(public_location, ST_MakePoint(106.8000, 10.8700)::geography, 1000)
				ORDER BY id
				""", String.class, SELLER_ID);

		assertThat(titles).containsExactly("Tin mẫu 1", "Tin mẫu 2", "Tin mẫu 3", "Tin mẫu 4", "Tin mẫu 5", "Tin mẫu 6",
				"Tin mẫu 7", "Tin mẫu 8", "Tin mẫu 9", "Tin mẫu 10");
	}

}
