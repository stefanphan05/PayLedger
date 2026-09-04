package com.stefan.payment_api_service.idempotency.repository

import com.stefan.payment_api_service.idempotency.config.IdempotencyProperties
import com.stefan.payment_api_service.idempotency.model.IdempotencyRecord
import com.stefan.payment_api_service.idempotency.model.IdempotencyState
import org.springframework.data.redis.connection.SetCondition
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.types.Expiration
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Repository
class IdempotencyRepository (
    private val redis: StringRedisTemplate,
    private val jsonMapper: JsonMapper,
    private val properties: IdempotencyProperties
) {
    /**
     * Tries to "claim" this idempotency key for the very first time.
     *
     * Uses Redis's SETNX ("set if not exists") under the hood via setIfAbsent —
     * this is atomic, meaning if two requests with the same key arrive at the
     * exact same moment, only ONE of them can possibly win this call. The other
     * gets `false` back, so we never accidentally process the same payment twice.
     *
     * @return true if THIS request just claimed the key (nobody had it before).
     *         false if the key already exists (someone else already claimed it).
     */
    fun reserve(
        userId: UUID,
        key: String,
        requestHash: String,
    ): Boolean {
        val newReservation = IdempotencyRecord(
            state = IdempotencyState.NEW,
            requestHash = requestHash,
        )

        val reservationJson = jsonMapper.writeValueAsString(newReservation)

        val didWinReservation = redis
            .opsForValue()
            .setIfAbsent(
                redisKey(userId, key),
                reservationJson,
                properties.ttl
            )

        // setIfAbsent returns Boolean? (nullable) — only a plain `true` counts as a win
        return didWinReservation == true
    }

    /**
     * Looks up whatever is currently stored for this key, if anything.
     *
     * @return null if no record exists (key never used, or it expired).
     *         the parsed record otherwise, so the caller can check its state.
     */
    fun find(userId: UUID, key: String): IdempotencyRecord? {
        val storedJson = redis
            .opsForValue()
            .get(redisKey(userId, key))
            ?: return null

        return jsonMapper.readValue(storedJson, IdempotencyRecord::class.java)
    }

    /**
     * Marks that we've started actually processing the request (right before
     * the real transaction creation happens). This is mostly for debugging —
     * if the server crashes after this point, we know it died mid-transaction
     * rather than before starting.
     */
    fun markInProgress(userId: UUID, key: String, requestHash: String) {
        val inProcessRecord = IdempotencyRecord(
            state = IdempotencyState.IN_PROGRESS,
            requestHash = requestHash,
        )

        writeKeepingExpiry(userId, key, inProcessRecord)
    }

    /** Marks the request as successfully finished, storing the response so a retry can replay it. */
    fun complete(userId: UUID, key: String, record: IdempotencyRecord) {
        writeKeepingExpiry(userId, key, record)
    }

    /** Marks the request as rejected for a reason that will never change (e.g. self-transfer). */
    fun fail(userId: UUID, key: String, record: IdempotencyRecord) {
        writeKeepingExpiry(userId, key, record)
    }

    /**
     * Deletes the key entirely. Used when something went wrong in a way that
     * might succeed if tried again (e.g. a temporary database hiccup) — so we
     * free up the key and let the client retry with the same Idempotency-Key.
     */
    fun release(userId: UUID, key: String) {
        redis.delete(redisKey(userId, key))
    }

    /**
     * Keeps the key but shortens its life, for a failure where the database write
     * may already have committed. Deleting it would let a retry create a second
     * payment; keeping it for the full TTL would strand the payment for a day.
     */
    fun holdBriefly(userId: UUID, key: String) {
        redis.expire(redisKey(userId, key), properties.ambiguousFailureHold)
    }

    /**
     * All three state transitions above (markInProgress, complete, fail) do the
     * exact same low-level thing: overwrite the stored value, but WITHOUT
     * resetting the TTL countdown. This one private method is the single place
     * that actually talks to Redis for all of them.
     *
     * We use KEEPTTL specifically because ValueOperations().set() has no
     * built-in way to update a value while preserving its existing expiry —
     * plain `set()` would restart the 24h countdown every time we write,
     * which we don't want (the window should count down from the very first
     * request, not reset every time the state changes).
     *
     * Since ValueOperations cannot express KEEPTTL, this drops to the raw
     * command. StringRedisTemplate serializes with UTF-8, which Kotlin's
     * toByteArray() matches by default.
     *
     * Edge case: SET ... KEEPTTL on a key that no longer exists creates it with
     * NO expiry. That needs the full TTL to elapse between reserve() and this
     * write — impossible for a live request — so it is not guarded here.
     */
    private fun writeKeepingExpiry(userId: UUID, key: String, record: IdempotencyRecord) {
        val recordJson = jsonMapper.writeValueAsString(record)

        redis.execute(
            RedisCallback { connection ->
                connection.stringCommands().set(
                    redisKey(userId, key).toByteArray(),
                    recordJson.toByteArray(),
                    SetCondition.upsert(),
                    Expiration.keepTtl(),
                )
            }
        )
    }

    /** Builds the actual Redis key string, namespaced per user so two users can never collide. */
    private fun redisKey(userId: UUID, key: String) = "idempotency:$userId:$key"
}