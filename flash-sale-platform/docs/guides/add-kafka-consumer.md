# 새 Kafka Consumer 추가 가이드

> payment-service의 **"주문 이벤트 수신 → 결제 처리"**를 예제로 Step-by-Step 따라하기

---

## 목차

1. [전체 흐름 미리보기](#1-전체-흐름-미리보기)
2. [Step 1: 토픽 등록](#step-1-토픽-등록)
3. [Step 2: 이벤트 정의](#step-2-이벤트-정의)
4. [Step 3: Consumer 작성](#step-3-consumer-작성)
5. [Step 4: 멱등성 처리](#step-4-멱등성-처리)
6. [Step 5: DLQ 설정](#step-5-dlq-설정)
7. [Step 6: 테스트](#step-6-테스트)
8. [전체 토픽 목록 + 이벤트 흐름도](#전체-토픽-목록--이벤트-흐름도)

---

## 1. 전체 흐름 미리보기

```
order-service                     payment-service
┌──────────────┐                 ┌──────────────────────────┐
│ 주문 생성     │                 │                          │
│   ↓          │  Kafka          │ OrderPlacedEventConsumer  │
│ 이벤트 발행 ──┼──────────────▶ │   ↓                      │
│              │  order.placed   │ 멱등성 체크 (Redis)        │
└──────────────┘                 │   ↓                      │
                                 │ 결제 처리                  │
                                 │   ↓ (성공)                │
                                 │ payment.completed 발행    │
                                 │   ↓ (실패)                │
                                 │ payment.failed 발행       │
                                 └──────────────────────────┘
```

---

## Step 1: 토픽 등록

> `common/infrastructure/src/.../kafka/KafkaTopics.kt`에 토픽 상수를 추가한다.

```kotlin
object KafkaTopics {
    object Order {
        const val PLACED = "flashsale.order.placed"      // ← 이미 존재
        const val CANCELLED = "flashsale.order.cancelled"
        const val COMPLETED = "flashsale.order.completed"
    }
    object Payment {
        const val REQUESTED = "flashsale.payment.requested"
        const val COMPLETED = "flashsale.payment.completed"
        const val FAILED = "flashsale.payment.failed"
    }
    // 새 토픽이 필요하면 여기에 추가
    // object NewDomain {
    //     const val EVENT_NAME = "flashsale.{domain}.{event}"
    // }

    fun dlq(originalTopic: String) = "$originalTopic.dlq"
}
```

### 토픽 네이밍 규칙

```
flashsale.{도메인}.{이벤트}

예시:
  flashsale.order.placed        → 주문 생성됨
  flashsale.payment.completed   → 결제 완료됨
  flashsale.stock.decremented   → 재고 차감됨
```

---

## Step 2: 이벤트 정의

> DomainEvent 인터페이스를 구현하는 data class를 정의한다.

### 이벤트를 발행하는 쪽 (order-service)

```kotlin
package com.flashsale.order.domain.model

import com.flashsale.common.domain.DomainEvent
import com.flashsale.common.domain.IdGenerator
import java.time.Instant

/** 주문 생성 이벤트 — Kafka로 발행됨 */
data class OrderPlacedEvent(
    override val aggregateId: String,     // orderId (파티셔닝 키)
    override val eventType: String = "order.placed",
    override val occurredAt: Instant = Instant.now(),
    override val eventId: String = IdGenerator.generate(),  // 멱등성 보장
    val userId: String,
    val productId: String,
    val quantity: Int,
) : DomainEvent
```

### 이벤트를 수신하는 쪽 (payment-service)

같은 이벤트 클래스를 사용하거나, 수신에 필요한 필드만 가진 별도 클래스를 정의한다.

```kotlin
package com.flashsale.payment.adapter.`in`.kafka

/** 수신용 이벤트 DTO (필요한 필드만) */
data class OrderPlacedEventDto(
    val aggregateId: String,
    val eventId: String,
    val userId: String,
    val productId: String,
    val quantity: Int,
)
```

---

## Step 3: Consumer 작성

### @KafkaListener 기반 Consumer

```kotlin
package com.flashsale.payment.adapter.`in`.kafka

import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import com.flashsale.common.kafka.KafkaTopics
import com.flashsale.common.logging.Log
import com.flashsale.common.logging.MdcKeys
import com.flashsale.common.logging.withMdc
import com.flashsale.payment.application.port.`in`.ProcessPaymentUseCase
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class OrderPlacedEventConsumer(
    private val processPaymentUseCase: ProcessPaymentUseCase,
    private val idempotencyChecker: IdempotencyChecker,
    private val objectMapper: ObjectMapper,
) {
    companion object : Log

    @KafkaListener(
        topics = [KafkaTopics.Order.PLACED],
        groupId = "\${spring.application.name}",  // payment-service
    )
    suspend fun handle(record: ConsumerRecord<String, String>) {
        val event = objectMapper.readValue<OrderPlacedEventDto>(record.value())

        withMdc(MdcKeys.ORDER_ID, event.aggregateId) {
            logger.info { "주문 이벤트 수신: orderId=${event.aggregateId}" }

            // 멱등성 체크
            if (idempotencyChecker.isDuplicate(event.eventId)) {
                logger.warn { "중복 이벤트 무시: eventId=${event.eventId}" }
                return@withMdc
            }

            // 결제 처리
            processPaymentUseCase.execute(
                ProcessPaymentUseCase.Command(
                    orderId = event.aggregateId,
                    userId = event.userId,
                    productId = event.productId,
                    quantity = event.quantity,
                ),
            )

            // 멱등성 키 저장
            idempotencyChecker.markProcessed(event.eventId)
            logger.info { "결제 처리 완료: orderId=${event.aggregateId}" }
        }
    }
}
```

### ConsumerRecord에서 얻을 수 있는 정보

```kotlin
record.topic()       // 토픽명: "flashsale.order.placed"
record.partition()   // 파티션 번호: 0, 1, 2...
record.offset()      // 오프셋 (읽은 위치)
record.key()         // 메시지 키 (orderId)
record.value()       // 메시지 값 (JSON 문자열)
record.timestamp()   // 발행 시각
```

---

## Step 4: 멱등성 처리

> Kafka는 at-least-once 전달을 보장한다. 같은 메시지가 2번 올 수 있으므로 **멱등성 체크 필수**.

### Redis SETNX + TTL로 구현

```kotlin
package com.flashsale.payment.adapter.`in`.kafka

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class IdempotencyChecker(
    private val redisTemplate: ReactiveStringRedisTemplate,
) {
    companion object {
        private val TTL = Duration.ofHours(24)  // 24시간 동안 중복 체크
        private const val KEY_PREFIX = "idempotency:payment:"
    }

    /** 이미 처리된 이벤트인지 확인 */
    suspend fun isDuplicate(eventId: String): Boolean =
        redisTemplate.hasKey("$KEY_PREFIX$eventId").awaitSingle()

    /** 처리 완료 표시 (TTL 24시간) */
    suspend fun markProcessed(eventId: String) {
        redisTemplate.opsForValue()
            .set("$KEY_PREFIX$eventId", "1", TTL)
            .awaitSingleOrNull()
    }
}
```

### 멱등성 처리 흐름

```
메시지 수신
  ↓
Redis에 eventId 존재? ─── YES → 무시 (이미 처리됨)
  │ NO
  ↓
비즈니스 로직 실행
  ↓
Redis에 eventId 저장 (TTL 24시간)
  ↓
완료
```

---

## Step 5: DLQ 설정

> 재시도 후에도 실패하면 DLQ(Dead Letter Queue)로 이동시킨다.

### Kafka 에러 핸들러 설정

```kotlin
package com.flashsale.payment.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

@Configuration
class KafkaConsumerConfig {

    @Bean
    fun kafkaErrorHandler(
        kafkaTemplate: KafkaTemplate<String, String>,
    ): CommonErrorHandler =
        DefaultErrorHandler(
            // 재시도 모두 실패 시 → DLQ 토픽으로 이동
            // 원본 토픽: flashsale.order.placed
            // DLQ 토픽:  flashsale.order.placed.DLT (Spring 기본 접미사)
            DeadLetterPublishingRecoverer(kafkaTemplate),
            // 1초 간격, 최대 3번 재시도
            FixedBackOff(1000L, 3L),
        )
}
```

### DLQ 흐름

```
1. 메시지 수신
2. 처리 시도 → 예외 발생!
3. 1초 대기 → 재시도 1회 → 실패
4. 1초 대기 → 재시도 2회 → 실패
5. 1초 대기 → 재시도 3회 → 실패
6. DLQ 토픽으로 이동: flashsale.order.placed.DLT
7. 나중에 수동 확인 후 재처리
```

### 주의: 예외를 삼키지 않기

```kotlin
// ❌ 예외를 catch하면 DLQ로 가지 않음
@KafkaListener(topics = ["order.placed"])
fun handle(record: ConsumerRecord<String, String>) {
    try {
        process(record)
    } catch (e: Exception) {
        logger.error { "실패: ${e.message}" }  // 로그만 찍고 넘어감 → DLQ 안 감!
    }
}

// ✅ 예외를 전파해야 재시도 + DLQ 동작
@KafkaListener(topics = ["order.placed"])
fun handle(record: ConsumerRecord<String, String>) {
    process(record)  // 실패하면 예외 전파 → 재시도 → DLQ
}
```

---

## Step 6: 테스트

### Consumer 단위 테스트

```kotlin
class OrderPlacedEventConsumerTest : FunSpec({
    val processPaymentUseCase = mockk<ProcessPaymentUseCase>()
    val idempotencyChecker = mockk<IdempotencyChecker>()
    val objectMapper = jacksonObjectMapper()
    val consumer = OrderPlacedEventConsumer(
        processPaymentUseCase, idempotencyChecker, objectMapper,
    )

    test("새 이벤트를 수신하면 결제를 처리한다") {
        // given
        val event = OrderPlacedEventDto(
            aggregateId = "order-1",
            eventId = "evt-1",
            userId = "user-1",
            productId = "prod-1",
            quantity = 1,
        )
        val record = ConsumerRecord(
            KafkaTopics.Order.PLACED, 0, 0L, "order-1",
            objectMapper.writeValueAsString(event),
        )

        coEvery { idempotencyChecker.isDuplicate("evt-1") } returns false
        coEvery { processPaymentUseCase.execute(any()) } returns Result.success(Unit)
        coEvery { idempotencyChecker.markProcessed("evt-1") } just runs

        // when
        consumer.handle(record)

        // then
        coVerify(exactly = 1) { processPaymentUseCase.execute(any()) }
        coVerify(exactly = 1) { idempotencyChecker.markProcessed("evt-1") }
    }

    test("중복 이벤트는 무시한다") {
        val event = OrderPlacedEventDto(
            aggregateId = "order-1", eventId = "evt-1",
            userId = "user-1", productId = "prod-1", quantity = 1,
        )
        val record = ConsumerRecord(
            KafkaTopics.Order.PLACED, 0, 0L, "order-1",
            objectMapper.writeValueAsString(event),
        )

        coEvery { idempotencyChecker.isDuplicate("evt-1") } returns true

        consumer.handle(record)

        coVerify(exactly = 0) { processPaymentUseCase.execute(any()) }
    }
})
```

### 통합 테스트 (Testcontainers Kafka)

```kotlin
@SpringBootTest
class OrderPlacedEventConsumerIntegrationTest : IntegrationTestBase(), FunSpec({
    val kafkaTemplate = autowired<KafkaTemplate<String, String>>()
    val objectMapper = autowired<ObjectMapper>()

    test("Kafka 메시지를 수신하여 결제가 처리된다") {
        // given
        val event = mapOf(
            "aggregateId" to "order-1",
            "eventId" to IdGenerator.generate(),
            "userId" to "user-1",
            "productId" to "prod-1",
            "quantity" to 1,
        )

        // when: Kafka에 메시지 발행
        kafkaTemplate.send(
            KafkaTopics.Order.PLACED,
            "order-1",
            objectMapper.writeValueAsString(event),
        ).get()

        // then: 일정 시간 내에 결제가 처리되었는지 확인
        eventually(5.seconds) {
            // DB 또는 Redis에서 결제 상태 확인
            val processed = redisTemplate.hasKey("idempotency:payment:${event["eventId"]}")
                .awaitSingle()
            processed shouldBe true
        }
    }
})
```

---

## 전체 토픽 목록 + 이벤트 흐름도

### 현재 토픽 목록 (KafkaTopics.kt 기반)

| 토픽 | Producer | Consumer | 설명 |
|------|----------|----------|------|
| `flashsale.order.placed` | order-service | payment-service | 주문 생성됨 |
| `flashsale.order.cancelled` | order-service | notification-service | 주문 취소됨 |
| `flashsale.order.completed` | order-service | notification-service | 주문 완료됨 |
| `flashsale.payment.requested` | payment-service | - | 결제 요청됨 |
| `flashsale.payment.completed` | payment-service | order-service, notification-service | 결제 완료됨 |
| `flashsale.payment.failed` | payment-service | order-service | 결제 실패함 |
| `flashsale.stock.decremented` | order-service | - | 재고 차감됨 |
| `flashsale.stock.restored` | order-service | - | 재고 복원됨 |
| `flashsale.notification.send-requested` | order/payment | notification-service | 알림 요청 |

### 이벤트 흐름도

```
                    flashsale.order.placed
order-service ─────────────────────────────▶ payment-service
     │                                           │
     │              flashsale.payment.completed   │
     ◀───────────────────────────────────────────┘
     │              flashsale.payment.failed       │
     ◀───────────────────────────────────────────┘
     │
     │  flashsale.order.completed / cancelled
     └─────────────────────────────────────▶ notification-service
```
