package com.stefan.ledger_service

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Proves the context wires up: entities map to the schema Flyway builds, and every
 * bean's dependencies resolve. Needs a real database for that to mean anything.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class LedgerServiceApplicationTests {

	companion object {
		@Container
		@ServiceConnection
		@JvmStatic
		val postgres: PostgreSQLContainer<Nothing> =
			PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))
	}

	@Test
	fun contextLoads() {
	}
}
