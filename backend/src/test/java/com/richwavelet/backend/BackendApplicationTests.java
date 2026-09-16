package com.richwavelet.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the whole application context.
 *
 * <p>The {@code test} profile swaps the Supabase PostgreSQL datasource for an in-memory H2
 * database (see {@code src/test/resources/application-test.properties}), so the context can
 * be verified without any external service.
 */
@SpringBootTest
@ActiveProfiles("test")
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
