package vn.edu.uit.nearby.nearby_backend.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.uit.nearby.nearby_backend.support.IntegrationTest;

@IntegrationTest
@Transactional
class ListingDemoIntegrationTests {

	private static final String SEED = "/db/demo/seed_demo.sql";

	private static final UUID SELLER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MockMvc mvc;

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
				JOIN user_areas a ON a.id = l.area_id AND a.user_id = l.seller_id
				JOIN listing_images i ON i.listing_id = l.id AND i.owner_id = l.seller_id
				WHERE l.seller_id = ? AND c.code = 'ELECTRONICS' AND a.status = 'VERIFIED'
				  AND i.position = 1 AND i.processing_status = 'READY'
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

	@Test
	@Sql(SEED)
	void searchFiltersOnPublicPointsNearTheRadiusWithoutReturningCoordinates() throws Exception {
		UUID inside = UUID.fromString("20000000-0000-4000-8000-000000000010");
		UUID outside = UUID.fromString("20000000-0000-4000-8000-000000000011");

		// The two points are about 998.5 m and 1001.9 m from the search center.
		// Swap exact points so filtering on them would return the wrong listing.
		int updated = this.jdbc.update("""
				WITH points(id, public_lat, exact_lat) AS (
				    VALUES (CAST(? AS uuid), 10.87898, 10.87901),
				           (CAST(? AS uuid), 10.87901, 10.87898)
				)
				UPDATE listings l
				SET description = 'se114radiusboundary',
				    public_location = ST_SetSRID(ST_MakePoint(106.8000, p.public_lat), 4326)::geography,
				    exact_location = ST_SetSRID(ST_MakePoint(106.8000, p.exact_lat), 4326)::geography
				FROM points p WHERE l.id = p.id
				""", inside, outside);
		assertThat(updated).isEqualTo(2);

		searchAtRadius("1").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", contains(inside.toString())))
			.andExpect(jsonPath("$.items[0].distanceMeters").value(1000));
		searchAtRadius("1.01").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", contains(inside.toString(), outside.toString())))
			.andExpect(jsonPath("$..pin").isEmpty())
			.andExpect(jsonPath("$..publicPoint").isEmpty())
			.andExpect(jsonPath("$..latitude").isEmpty())
			.andExpect(jsonPath("$..longitude").isEmpty());
	}

	private ResultActions searchAtRadius(String radiusKm) throws Exception {
		return this.mvc.perform(get("/api/v1/search").param("q", "se114radiusboundary")
			.param("lat", "10.8700")
			.param("lon", "106.8000")
			.param("radiusKm", radiusKm)
			.param("sort", "NEAREST"));
	}

}
