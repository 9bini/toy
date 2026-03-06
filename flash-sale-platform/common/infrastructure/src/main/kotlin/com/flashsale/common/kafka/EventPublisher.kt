package com.flashsale.common.kafka

import com.flashsale.common.domain.DomainEvent

/**
 * 도메인 이벤트 발행 인터페이스.
 * Kafka 구현체(KafkaEventPublisher)가 기본 제공되며,
 * 테스트에서는 Mock으로 대체할 수 있다.
 */
interface EventPublisher {
    /**
     * 도메인 이벤트를 지정된 토픽에 발행한다.
     *
     * @param topic Kafka 토픽 (KafkaTopics 상수 사용)
     * @param event 발행할 도메인 이벤트 (aggregateId가 파티션 키로 사용됨)
     */
    suspend fun publish(topic: String, event: DomainEvent)
}
