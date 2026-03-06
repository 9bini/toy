package com.flashsale.common.redis

import com.flashsale.common.config.TimeoutProperties
import com.flashsale.common.logging.Log
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withTimeout
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

/**
 * Redis Lua Script를 코루틴으로 실행하는 유틸리티.
 * 각 서비스가 자체 Lua Script를 정의하여 이 executor로 실행한다.
 *
 * 사용 예시:
 * ```kotlin
 * val script = RedisScript.of<Long>("""
 *     local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
 *     if stock >= tonumber(ARGV[1]) then
 *         return redis.call('DECRBY', KEYS[1], ARGV[1])
 *     end
 *     return -1
 * """.trimIndent(), Long::class.java)
 *
 * val remaining = scriptExecutor.execute(
 *     script = script,
 *     keys = listOf(RedisKeys.Stock.remaining(productId)),
 *     args = listOf(quantity.toString()),
 * )
 * ```
 */
@Component
class RedisScriptExecutor(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val timeouts: TimeoutProperties,
) {
    companion object : Log

    /**
     * Lua Script를 실행하고 결과를 반환한다.
     *
     * @param script Redis Lua Script (RedisScript.of()로 생성)
     * @param keys KEYS 배열
     * @param args ARGV 배열
     * @return 스크립트 실행 결과
     */
    suspend fun <T : Any> execute(
        script: RedisScript<T>,
        keys: List<String>,
        args: List<String> = emptyList(),
    ): T {
        logger.debug { "Executing Redis script: keys=$keys" }

        return withTimeout(timeouts.redisLuaScript) {
            redisTemplate.execute(script, keys, args).awaitSingle()
        }
    }
}
