package com.flashsale.queue.adapter.out.redis

import com.flashsale.common.test.IntegrationTestBase
import com.flashsale.queue.domain.QueueEntry
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant

@SpringBootTest
class RedisQueueAdapterTest : DescribeSpec() {
    companion object : IntegrationTestBase() {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerProperties(registry)
    }

    @Autowired
    private lateinit var adapter: RedisQueueAdapter

    @Autowired
    private lateinit var redisTemplate: ReactiveStringRedisTemplate

    init {
        extensions(SpringExtension())

        beforeEach {
            redisTemplate.delete("queue:waiting:test-sale").block()
        }

        describe("add") {
            context("새 사용자를 추가하면") {
                it("true를 반환한다") {
                    val entry = QueueEntry("test-sale", "user-001", Instant.now())
                    adapter.add(entry) shouldBe true
                }
            }

            context("같은 사용자를 다시 추가하면") {
                it("false를 반환한다") {
                    val entry = QueueEntry("test-sale", "user-001", Instant.now())
                    adapter.add(entry)

                    adapter.add(entry) shouldBe false
                }
            }
        }

        describe("getPosition") {
            context("대기열에 사용자가 있으면") {
                it("1-based 순번을 반환한다") {
                    val now = Instant.now()
                    adapter.add(QueueEntry("test-sale", "user-001", now))
                    adapter.add(QueueEntry("test-sale", "user-002", now.plusMillis(1)))
                    adapter.add(QueueEntry("test-sale", "user-003", now.plusMillis(2)))

                    adapter.getPosition("test-sale", "user-001") shouldBe 1L
                    adapter.getPosition("test-sale", "user-002") shouldBe 2L
                    adapter.getPosition("test-sale", "user-003") shouldBe 3L
                }
            }

            context("대기열에 사용자가 없으면") {
                it("null을 반환한다") {
                    adapter.getPosition("test-sale", "unknown-user").shouldBeNull()
                }
            }
        }
    }
}
