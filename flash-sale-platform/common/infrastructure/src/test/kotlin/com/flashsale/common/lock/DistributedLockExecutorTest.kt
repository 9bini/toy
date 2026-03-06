package com.flashsale.common.lock

import com.flashsale.common.config.TimeoutProperties
import com.flashsale.common.domain.Result
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import java.util.concurrent.TimeUnit

class DistributedLockExecutorTest : DescribeSpec({
    val redissonClient = mockk<RedissonClient>()
    val timeouts = TimeoutProperties()
    val sut = DistributedLockExecutor(redissonClient, timeouts)

    describe("withLock") {
        val key = "test:lock:key"
        val lock = mockk<RLock>(relaxed = true)

        beforeEach {
            every { redissonClient.getLock(key) } returns lock
        }

        context("락 획득에 성공하면") {
            it("block을 실행하고 Result.Success를 반환한다") {
                every {
                    lock.tryLock(any<Long>(), any<Long>(), any<TimeUnit>())
                } returns true
                every { lock.isHeldByCurrentThread } returns true

                val result = sut.withLock(key) { "done" }

                result.shouldBeInstanceOf<Result.Success<String>>()
                (result as Result.Success).value shouldBe "done"
            }

            it("block 완료 후 unlock을 호출한다") {
                every {
                    lock.tryLock(any<Long>(), any<Long>(), any<TimeUnit>())
                } returns true
                every { lock.isHeldByCurrentThread } returns true

                sut.withLock(key) { "done" }

                verify { lock.unlock() }
            }
        }

        context("락 획득에 실패하면") {
            it("AcquisitionFailed를 반환한다") {
                every {
                    lock.tryLock(any<Long>(), any<Long>(), any<TimeUnit>())
                } returns false

                val result = sut.withLock(key) { "done" }

                result.shouldBeInstanceOf<Result.Failure<*>>()
                val error = (result as Result.Failure).error as LockError.AcquisitionFailed
                error.key shouldBe key
            }
        }

        context("block 실행 중 예외가 발생하면") {
            it("ExecutionFailed를 반환하고 unlock을 호출한다") {
                every {
                    lock.tryLock(any<Long>(), any<Long>(), any<TimeUnit>())
                } returns true
                every { lock.isHeldByCurrentThread } returns true

                val result = sut.withLock<String>(key) { throw RuntimeException("test error") }

                result.shouldBeInstanceOf<Result.Failure<*>>()
                val error = (result as Result.Failure).error as LockError.ExecutionFailed
                error.key shouldBe key
                error.cause shouldBe "test error"
                verify { lock.unlock() }
            }
        }
    }
})
