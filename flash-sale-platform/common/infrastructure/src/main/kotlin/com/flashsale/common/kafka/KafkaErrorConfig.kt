package com.flashsale.common.kafka

import com.flashsale.common.logging.Log
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * Kafka Consumer 공통 에러 처리 설정.
 *
 * 메시지 처리 실패 시:
 * 1. 3회 재시도 (1초 간격)
 * 2. 재시도 실패 → DLQ 토픽으로 전송 (원본 토픽명 + ".dlq")
 *
 * 각 서비스에서 별도 ErrorHandler를 등록하지 않으면 이 설정이 적용된다.
 */
@Configuration
@ConditionalOnClass(KafkaOperations::class)
class KafkaErrorConfig {
    companion object : Log

    @Bean
    fun kafkaErrorHandler(kafkaOperations: KafkaOperations<Any, Any>): CommonErrorHandler {
        val recoverer =
            DeadLetterPublishingRecoverer(kafkaOperations) { record: ConsumerRecord<*, *>, _: Exception ->
                TopicPartition(KafkaTopics.dlq(record.topic()), record.partition())
            }

        // 3회 재시도, 1초 간격. 이후 DLQ로 전송
        return DefaultErrorHandler(recoverer, FixedBackOff(1000L, 2L)).apply {
            setRetryListeners({ record, ex, attempt ->
                logger.warn { "Kafka 메시지 재시도 ${attempt}회: topic=${record?.topic()}, error=${ex.message}" }
            })
        }
    }
}
