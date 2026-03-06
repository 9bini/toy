package com.flashsale.common.kafka

import com.flashsale.common.config.TimeoutProperties
import com.flashsale.common.domain.DomainEvent
import com.flashsale.common.logging.Log
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeout
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Kafka를 통한 도메인 이벤트 발행 구현체.
 *
 * 사용 예시:
 * ```kotlin
 * eventPublisher.publish(KafkaTopics.Order.PLACED, orderPlacedEvent)
 * ```
 */
@Component
class KafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val timeouts: TimeoutProperties,
) : EventPublisher {
    companion object : Log

    override suspend fun publish(topic: String, event: DomainEvent) {
        logger.info { "Publishing event: topic=$topic, eventType=${event.eventType}, aggregateId=${event.aggregateId}" }

        withTimeout(timeouts.kafkaProduce) {
            Mono.fromCompletionStage(
                kafkaTemplate.send(topic, event.aggregateId, event),
            ).awaitSingle()
        }

        logger.debug { "Event published: topic=$topic, eventId=${event.eventId}" }
    }
}
