package com.flashsale.common.kafka

import com.flashsale.common.config.TimeoutProperties
import com.flashsale.common.domain.DomainEvent
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.util.concurrent.CompletableFuture

class KafkaEventPublisherTest : DescribeSpec({
    val kafkaTemplate = mockk<KafkaTemplate<String, Any>>()
    val timeouts = TimeoutProperties()
    val sut = KafkaEventPublisher(kafkaTemplate, timeouts)

    describe("publish") {
        val event = TestDomainEvent(
            aggregateId = "order-001",
            eventType = "order.placed",
            occurredAt = Instant.now(),
            eventId = "evt-001",
        )

        context("이벤트를 발행하면") {
            it("aggregateId를 key로 사용하여 Kafka에 전송한다") {
                val topicSlot = slot<String>()
                val keySlot = slot<String>()
                val valueSlot = slot<Any>()

                every {
                    kafkaTemplate.send(capture(topicSlot), capture(keySlot), capture(valueSlot))
                } returns completedSendResult("test-topic", "order-001")

                sut.publish("test-topic", event)

                topicSlot.captured shouldBe "test-topic"
                keySlot.captured shouldBe "order-001"
                valueSlot.captured shouldBe event
            }
        }
    }
})

private data class TestDomainEvent(
    override val aggregateId: String,
    override val eventType: String,
    override val occurredAt: Instant,
    override val eventId: String,
) : DomainEvent

private fun completedSendResult(
    topic: String,
    key: String,
): CompletableFuture<SendResult<String, Any>> {
    val metadata = RecordMetadata(TopicPartition(topic, 0), 0, 0, 0, 0, 0)
    val record = ProducerRecord<String, Any>(topic, key, "value")
    return CompletableFuture.completedFuture(SendResult(record, metadata))
}
