package com.etp.migration.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.connector.target.postgresql.PostgreSqlTargetIdentityFloorProbe;
import com.etp.migration.mapping.TargetIdentitySpec;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class TargetIdentityAllocatorIntegrationTest {

	@Autowired
	private PostgresTargetIdentityAllocator allocator;

	@Autowired
	private PostgreSqlTargetIdentityFloorProbe floorProbe;

	@Autowired
	private GeneratedTargetIdentityService service;

	@Autowired
	@Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN)
	private DataSource targetDataSource;

	@Autowired
	private DataSource dataSource;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void clearAllocators() throws Exception {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DELETE FROM target_identity_allocator");
			statement.execute("DELETE FROM migration_identity_allocation");
		}
	}

	@Test
	void reservesARangeFromTheExistingFloorAndThenContinues() throws Exception {
		execute("""
				CREATE TABLE IF NOT EXISTS id_floor.sample (
				  id varchar(64) PRIMARY KEY
				)
				""");
		execute("TRUNCATE TABLE id_floor.sample");
		execute("INSERT INTO id_floor.sample (id) VALUES ('PFX-000001250'), ('PFX-LOC-000000050'), ('OTHER-000009999')");
		IdentityFloor floor = () -> floorProbe.existingMaximum("id_floor", "sample", "id", "PFX-", 9);
		long first = allocator.reserve("PFX-", 100, floor);
		assertThat(first).isEqualTo(1251L);
		execute("INSERT INTO id_floor.sample (id) VALUES ('PFX-000009999')");
		long second = allocator.reserve("PFX-", 50, floor);
		assertThat(second).isEqualTo(1351L);
		assertThat(PrefixSequenceFormatter.format("PFX-", 9, first)).isEqualTo("PFX-000001251");
		assertThat(PrefixSequenceFormatter.format("PFX-", 9, second + 49)).isEqualTo("PFX-000001400");
	}

	@Test
	void concurrentReservationsDoNotOverlap() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<Long> first = pool.submit(() -> {
				start.await();
				return allocator.reserve("CONC-", 100, () -> 0L);
			});
			Future<Long> second = pool.submit(() -> {
				start.await();
				return allocator.reserve("CONC-", 100, () -> 0L);
			});
			start.countDown();
			long low = Math.min(first.get(), second.get());
			long high = Math.max(first.get(), second.get());
			assertThat(low).isEqualTo(1L);
			assertThat(high).isEqualTo(101L);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void twoRunsDoNotReceiveTheSameNumbersAndARetryReusesThem() {
		UUID firstRun = UUID.randomUUID();
		UUID secondRun = UUID.randomUUID();
		TargetIdentitySpec spec = new TargetIdentitySpec("PREFIX_SEQUENCE", "RUN-", 9);
		var first = service.assign(firstRun, "Widget", List.of("W1", "W2"), spec, null, "widget", "id");
		var second = service.assign(secondRun, "Widget", List.of("W9"), spec, null, "widget", "id");
		var retry = service.assign(firstRun, "Widget", List.of("W2", "W1"), spec, null, "widget", "id");
		assertThat(first).containsEntry("W1", "RUN-000000001").containsEntry("W2", "RUN-000000002");
		assertThat(second).containsEntry("W9", "RUN-000000003");
		assertThat(retry).containsEntry("W1", "RUN-000000001").containsEntry("W2", "RUN-000000002");
	}

	@Test
	void sameTableDifferentPrefixesStayIndependent() throws Exception {
		execute("""
				CREATE TABLE IF NOT EXISTS id_floor.shared_ids (
				  id varchar(64) PRIMARY KEY
				)
				""");
		execute("TRUNCATE TABLE id_floor.shared_ids");
		execute("INSERT INTO id_floor.shared_ids (id) VALUES ('LOC-000001250'), ('GEO-LOC-000000010')");
		long address = allocator.reserve(
				"LOC-",
				1,
				() -> floorProbe.existingMaximum("id_floor", "shared_ids", "id", "LOC-", 9));
		long point = allocator.reserve(
				"GEO-LOC-",
				1,
				() -> floorProbe.existingMaximum("id_floor", "shared_ids", "id", "GEO-LOC-", 9));
		assertThat(address).isEqualTo(1251L);
		assertThat(point).isEqualTo(11L);
		assertThat(PrefixSequenceFormatter.format("LOC-", 9, address)).isEqualTo("LOC-000001251");
		assertThat(PrefixSequenceFormatter.format("GEO-LOC-", 9, point)).isEqualTo("GEO-LOC-000000011");
	}

	private void execute(String sql) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE SCHEMA IF NOT EXISTS id_floor");
			statement.execute(sql);
		}
	}
}
