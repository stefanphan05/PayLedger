package com.stefan.payment_api_service.controller

import com.stefan.payment_api_service.models.entity.User
import com.stefan.payment_api_service.repository.TransactionRepository
import com.stefan.payment_api_service.repository.UserRepository
import com.stefan.payment_api_service.service.IdempotencyService.Companion.HEADER_IDEMPOTENCY_KEY
import com.stefan.payment_api_service.service.IdempotencyService.Companion.HEADER_IDEMPOTENT_REPLAY
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import com.stefan.payment_api_service.security.UserSecurity

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class IdempotencyIntegrationTests @Autowired constructor(
    val mockMvc: MockMvc,
    val transactionRepository: TransactionRepository,
    val userRepository: UserRepository,
    val redis: StringRedisTemplate,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))

        @Container
        @ServiceConnection("redis")
        @JvmStatic
        val redisContainer: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private lateinit var sender: User
    private lateinit var recipient: User

    @BeforeEach
    fun seedUsers() {
        sender = userRepository.save(mockUser("sender@example.com"))
        recipient = userRepository.save(mockUser("recipient@example.com"))
    }

    @AfterEach
    fun cleanUp() {
        transactionRepository.deleteAll()
        userRepository.deleteAll()
        redis.connectionFactory?.getConnection()?.serverCommands()?.flushAll()
    }

    @Test
    fun `retrying with the same key returns the original transaction without creating a second one`() {
        val key = "retry-key-0001"

        val first = post(key, body(recipient.id))
        val second = post(key, body(recipient.id))

        first.andExpect(status().isCreated)
        second.andExpect(status().isCreated)
            .andExpect(header().string(HEADER_IDEMPOTENT_REPLAY, "true"))

        // The whole point: one payment, not two.
        assertEquals(1, transactionRepository.count())
        assertEquals(idOf(first.andReturn()), idOf(second.andReturn()))
    }

    @Test
    fun `the first response is not marked as a replay`() {
        post("first-key-0001", body(recipient.id))
            .andExpect(status().isCreated)
            .andExpect(header().doesNotExist(HEADER_IDEMPOTENT_REPLAY))
    }

    @Test
    fun `different keys create separate transactions`() {
        post("key-alpha-0001", body(recipient.id)).andExpect(status().isCreated)
        post("key-beta-00001", body(recipient.id)).andExpect(status().isCreated)

        assertEquals(2, transactionRepository.count())
    }

    @Test
    fun `reusing a key with a different amount is rejected as 422`() {
        post("reuse-key-0001", body(recipient.id, amount = "50.00")).andExpect(status().isCreated)

        post("reuse-key-0001", body(recipient.id, amount = "9999.00"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.title").value("Idempotency Key Reused"))

        assertEquals(1, transactionRepository.count())
    }

    @Test
    fun `a retry whose amount differs only in trailing zeros is treated as the same request`() {
        post("zeros-key-00001", body(recipient.id, amount = "50.00")).andExpect(status().isCreated)

        // "50.0" and "50.00" are the same payment, so this must replay, not 422.
        post("zeros-key-00001", body(recipient.id, amount = "50.0"))
            .andExpect(status().isCreated)
            .andExpect(header().string(HEADER_IDEMPOTENT_REPLAY, "true"))

        assertEquals(1, transactionRepository.count())
    }

    @Test
    fun `a rejected self-transfer replays the same error and creates nothing`() {
        val key = "selftransfer-01"

        post(key, body(sender.id))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.title").value("Invalid Transfer"))

        // Replayed from the FAILED record rather than re-executed.
        post(key, body(sender.id))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.title").value("Invalid Transfer"))

        assertEquals(0, transactionRepository.count())
    }

    @Test
    fun `an unknown recipient replays the same 404`() {
        val key = "norecipient-001"
        val ghost = UUID.randomUUID()

        post(key, body(ghost))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.title").value("Recipient Not Found"))

        post(key, body(ghost))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.title").value("Recipient Not Found"))

        assertEquals(0, transactionRepository.count())
    }

    @Test
    fun `a missing Idempotency-Key header is a 400 naming the header`() {
        mockMvc.perform(
            post("/transactions")
                .with(user(principal()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(recipient.id))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.title").value("Missing Header"))
            .andExpect(jsonPath("$.errors['$HEADER_IDEMPOTENCY_KEY']").isArray)

        assertEquals(0, transactionRepository.count())
    }

    @Test
    fun `a malformed Idempotency-Key is a 400, not a 500`() {
        post("bad key!", body(recipient.id))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.title").value("Validation Error"))
            .andExpect(jsonPath("$.errors['$HEADER_IDEMPOTENCY_KEY']").isArray)

        assertEquals(0, transactionRepository.count())
    }

    @Test
    fun `two users may use the same key without colliding`() {
        val shared = "shared-key-0001"

        post(shared, body(recipient.id), as_ = principal(sender)).andExpect(status().isCreated)
        post(shared, body(sender.id), as_ = principal(recipient)).andExpect(status().isCreated)

        assertEquals(2, transactionRepository.count())
    }

    private fun post(key: String, json: String, as_: UserSecurity = principal()) =
        mockMvc.perform(
            post("/transactions")
                .with(user(as_))
                .header(HEADER_IDEMPOTENCY_KEY, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
        )

    private fun idOf(result: MvcResult): String =
        Regex("\"id\":\"([^\"]+)\"").find(result.response.contentAsString)!!.groupValues[1]

    private fun body(recipientId: UUID, amount: String = "15.90") =
        """{"amount":"$amount","currencyCode":"AUD","recipientId":"$recipientId"}"""

    private fun principal(of: User = sender) = UserSecurity(
        id = of.id,
        email = of.email,
        userPassword = "not-a-real-hash",
        userAuthorities = listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private fun mockUser(email: String) = User(
        firstName = "Test",
        lastName = "User",
        email = email,
        password = "not-a-real-hash",
    )
}
