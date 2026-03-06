package com.flashsale.common.redis

import com.flashsale.common.config.TimeoutProperties
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux

class RedisScriptExecutorTest : DescribeSpec({
    val redisTemplate = mockk<ReactiveStringRedisTemplate>()
    val timeouts = TimeoutProperties()
    val sut = RedisScriptExecutor(redisTemplate, timeouts)

    describe("execute") {
        context("Lua Script를 실행하면") {
            it("keys와 args를 전달하고 결과를 반환한다") {
                val script = RedisScript.of("return 42", Long::class.java)
                val keysSlot = slot<List<String>>()
                val argsSlot = slot<List<String>>()

                every {
                    redisTemplate.execute(any<RedisScript<Long>>(), capture(keysSlot), capture(argsSlot))
                } returns Flux.just(42L)

                val result = sut.execute(
                    script = script,
                    keys = listOf("stock:remaining:product-001"),
                    args = listOf("5"),
                )

                result shouldBe 42L
                keysSlot.captured shouldBe listOf("stock:remaining:product-001")
                argsSlot.captured shouldBe listOf("5")
            }
        }

        context("args가 비어있을 때") {
            it("빈 리스트를 전달한다") {
                val script = RedisScript.of("return 1", Long::class.java)

                every {
                    redisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>(), any<List<String>>())
                } returns Flux.just(1L)

                val result = sut.execute(
                    script = script,
                    keys = listOf("key1"),
                )

                result shouldBe 1L
            }
        }
    }
})
