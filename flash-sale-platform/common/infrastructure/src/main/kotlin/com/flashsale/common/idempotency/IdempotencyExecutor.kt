package com.flashsale.common.idempotency

import com.flashsale.common.config.TimeoutProperties
import com.flashsale.common.logging.Log
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withTimeout
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * Redis 기반 멱등성 보장 유틸리티.
 * Kafka Consumer에서 동일 메시지의 중복 처리를 방지한다.
 *
 * 사용 예시:
 * ```kotlin
 * val result = idempotencyExecutor.executeOnce(
 *     key = RedisKeys.Order.idempotencyKey("$orderId:$eventId"),
 * ) {
 *     orderService.processOrder(order)
 * }
 * if (result == null) {
 *     logger.info { "Duplicate event skipped: $eventId" }
 * }
 * ```
 */
@Component
class IdempotencyExecutor(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val timeouts: TimeoutProperties,
) {
    companion object : Log

    /**
     * 멱등성 키로 중복 실행을 방지한다.
     *
     * @param key 멱등성 키 (RedisKeys 상수 사용 권장)
     * @param ttl 키 만료 시간 (기본값: 24시간)
     * @param block 첫 실행 시에만 호출되는 블록
     * @return 첫 실행이면 block 결과, 이미 처리된 키면 null
     */
    suspend fun <T> executeOnce(
        key: String,
        ttl: Duration = 24.hours,
        block: suspend () -> T,
    ): T? {
        val isNew =
            withTimeout(timeouts.redisOperation) {
                redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", ttl.toJavaDuration())
                    .awaitSingle()
            }

        if (!isNew) {
            logger.debug { "Idempotency key already exists, skipping: key=$key" }
            return null
        }

        return block()
    }
}
