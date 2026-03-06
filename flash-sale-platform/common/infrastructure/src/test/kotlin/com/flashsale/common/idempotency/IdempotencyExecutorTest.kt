package com.flashsale.common.idempotency

import com.flashsale.common.config.TimeoutProperties
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.time.Duration as JavaDuration

class IdempotencyExecutorTest : DescribeSpec({
    val redisTemplate = mockk<ReactiveStringRedisTemplate>()
    val valueOps = mockk<ReactiveValueOperations<String, String>>()
    val timeouts = TimeoutProperties()
    val sut = IdempotencyExecutor(redisTemplate, timeouts)

    beforeEach {
        every { redisTemplate.opsForValue() } returns valueOps
    }

    describe("executeOnce") {
        val key = "order:idempotency:order-001:evt-001"

        context("새로운 키일 때") {
            it("block을 실행하고 결과를 반환한다") {
                every {
                    valueOps.setIfAbsent(key, "1", any<JavaDuration>())
                } returns Mono.just(true)

                var blockCalled = false
                val result = sut.executeOnce(key) {
                    blockCalled = true
                    "processed"
                }

                result shouldBe "processed"
                blockCalled shouldBe true
            }
        }

        context("이미 처리된 키일 때") {
            it("null을 반환하고 block을 실행하지 않는다") {
                every {
                    valueOps.setIfAbsent(key, "1", any<JavaDuration>())
                } returns Mono.just(false)

                var blockCalled = false
                val result = sut.executeOnce(key) {
                    blockCalled = true
                    "processed"
                }

                result shouldBe null
                blockCalled shouldBe false
            }
        }
    }
})
