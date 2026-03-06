package com.flashsale.common.lock

import com.flashsale.common.config.TimeoutProperties
import com.flashsale.common.domain.Result
import com.flashsale.common.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Redisson 분산 락을 코루틴으로 감싸는 유틸리티.
 *
 * 사용 예시:
 * ```kotlin
 * val result = lockExecutor.withLock("stock:lock:$productId") {
 *     stockPort.decrement(productId, quantity)
 * }
 * result.fold(
 *     onSuccess = { stock -> ... },
 *     onFailure = { error -> when (error) { ... } },
 * )
 * ```
 */
@Component
class DistributedLockExecutor(
    private val redissonClient: RedissonClient,
    private val timeouts: TimeoutProperties,
) {
    companion object : Log

    /**
     * 분산 락을 획득하고 block을 실행한다.
     *
     * @param key 락 키
     * @param waitTime 락 획득 대기 시간 (기본값: TimeoutProperties.distributedLockWait)
     * @param leaseTime 락 유지 시간 (기본값: TimeoutProperties.distributedLockLease)
     * @param block 락 획득 후 실행할 블록
     * @return 성공 시 block 결과, 실패 시 LockError
     */
    suspend fun <T> withLock(
        key: String,
        waitTime: Duration = timeouts.distributedLockWait,
        leaseTime: Duration = timeouts.distributedLockLease,
        block: suspend () -> T,
    ): Result<T, LockError> {
        val lock = redissonClient.getLock(key)

        // Redisson tryLock is blocking — isolate on IO dispatcher
        val acquired =
            withContext(Dispatchers.IO) {
                lock.tryLock(
                    waitTime.inWholeMilliseconds,
                    leaseTime.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                )
            }

        if (!acquired) {
            logger.warn { "Lock acquisition failed: key=$key, waitTime=$waitTime" }
            return Result.failure(LockError.AcquisitionFailed(key, waitTime))
        }

        return try {
            val result = block()
            Result.success(result)
        } catch (e: Exception) {
            logger.error(e) { "Execution failed while holding lock: key=$key" }
            Result.failure(LockError.ExecutionFailed(key, e.message ?: e::class.simpleName ?: "unknown"))
        } finally {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        }
    }
}

/**
 * 분산 락 관련 에러.
 */
sealed interface LockError {
    /** 대기 시간 내에 락을 획득하지 못함 */
    data class AcquisitionFailed(val key: String, val waitTime: Duration) : LockError

    /** 락 내부 실행 중 예외 발생 */
    data class ExecutionFailed(val key: String, val cause: String) : LockError
}
