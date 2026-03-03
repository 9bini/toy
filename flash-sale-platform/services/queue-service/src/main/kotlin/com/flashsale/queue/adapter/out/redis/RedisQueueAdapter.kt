package com.flashsale.queue.adapter.out.redis

import com.flashsale.common.config.TimeoutProperties
import com.flashsale.common.logging.Log
import com.flashsale.common.redis.RedisKeys
import com.flashsale.queue.application.port.out.QueuePort
import com.flashsale.queue.domain.QueueEntry
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withTimeout
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component

@Component
class RedisQueueAdapter(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val timeouts: TimeoutProperties,
) : QueuePort {
    companion object : Log

    override suspend fun add(entry: QueueEntry): Boolean =
        withTimeout(timeouts.redisOperation) {
            val key = RedisKeys.Queue.waiting(entry.saleEventId)
            val score = entry.enteredAt.toEpochMilli().toDouble()
            // ZADD: 새 멤버면 true, 기존 멤버면 false (score 업데이트됨)
            redisTemplate.opsForZSet()
                .add(key, entry.userId, score)
                .awaitSingle()
        }

    override suspend fun getPosition(saleEventId: String, userId: String): Long? =
        withTimeout(timeouts.redisOperation) {
            val key = RedisKeys.Queue.waiting(saleEventId)
            val rank = redisTemplate.opsForZSet()
                .rank(key, userId)
                .awaitFirstOrNull()
            rank?.plus(1) // 0-based → 1-based
        }
}
