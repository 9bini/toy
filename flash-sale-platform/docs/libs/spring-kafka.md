# Spring Kafka

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [핵심 개념](#3-핵심-개념)
4. [Producer (발행)](#4-producer-발행)
5. [Consumer (구독)](#5-consumer-구독)
6. [에러 처리와 DLQ](#6-에러-처리와-dlq)
7. [이 프로젝트에서의 활용](#7-이-프로젝트에서의-활용)
8. [자주 하는 실수 / 주의사항](#8-자주-하는-실수--주의사항)
9. [정리 / 한눈에 보기](#9-정리--한눈에-보기)
10. [더 알아보기](#10-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

Apache Kafka를 Spring 방식(@어노테이션 기반)으로 쉽게 사용할 수 있게 해주는 통합 라이브러리.

### 비유

Kafka가 "우체국"이라면, Spring Kafka는 "우체국 접수 창구 + 배달 시스템".
직접 우편함에 넣지 않고, 창구에서 접수하면 알아서 처리해준다.

---

## 2. 왜 필요한가?

### Kafka 클라이언트 직접 사용 (장황함)

```kotlin
// ❌ 순수 Kafka 클라이언트
val props = Properties()
props[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = "localhost:9092"
props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
val producer = KafkaProducer<String, String>(props)
producer.send(ProducerRecord("topic", "key", "value"))
producer.close()
```

### Spring Kafka 사용 (간결함)

```kotlin
// ✅ Spring Kafka
@Component
class EventPublisher(private val kafkaTemplate: KafkaTemplate<String, String>) {
    fun publish(topic: String, key: String, value: String) {
        kafkaTemplate.send(topic, key, value)
    }
}
```

---

## 3. 핵심 개념

### 3.1 KafkaTemplate (Producer)

메시지를 Kafka 토픽에 보내는 핵심 클래스.

```kotlin
@Component
class OrderEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    suspend fun publish(topic: String, key: String, event: Any) {
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(topic, key, json)
            .asDeferred()    // ListenableFuture → Deferred (코루틴)
            .await()         // 전송 완료 대기
    }
}
```

### send() 메서드 종류

```kotlin
kafkaTemplate.send("topic", "value")                    // 키 없음, 라운드 로빈
kafkaTemplate.send("topic", "key", "value")             // 키 지정 (순서 보장)
kafkaTemplate.send("topic", 0, "key", "value")          // 파티션 직접 지정
kafkaTemplate.send(ProducerRecord("topic", "key", "value"))  // 레코드 객체
```

### 3.2 @KafkaListener (Consumer)

토픽의 메시지를 자동으로 수신하는 어노테이션.

```kotlin
@Component
class PaymentEventConsumer {

    @KafkaListener(
        topics = ["flashsale.order.placed"],   // 구독할 토픽
        groupId = "payment-service"             // 소비자 그룹
    )
    fun handleOrderPlaced(record: ConsumerRecord<String, String>) {
        val event = objectMapper.readValue<OrderPlacedEvent>(record.value())
        paymentService.processPayment(event)
    }
}
```

### 3.3 직렬화 / 역직렬화

Kafka는 바이트 배열만 전송한다. 객체를 바이트로 변환(직렬화)해야 한다.

```
Producer: Kotlin 객체 → JSON 문자열 → 바이트 배열 → Kafka
Consumer: Kafka → 바이트 배열 → JSON 문자열 → Kotlin 객체
```

```yaml
# application.yml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
```

---

## 4. Producer (발행)

### 기본 설정

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all              # 모든 복제본 확인 (가장 안전)
      retries: 3             # 실패 시 재시도 횟수
```

### acks 옵션 상세

| acks | 동작 | 안전성 | 속도 |
|------|------|--------|------|
| `0` | 전송만 하고 확인 안 함 | 유실 가능 | 가장 빠름 |
| `1` | Leader만 확인 | 보통 | 보통 |
| **`all`** | Leader + 모든 Replica | **가장 안전** | 느림 |

### 코루틴에서 전송 완료 대기

```kotlin
// 방법 1: asDeferred().await()
kafkaTemplate.send(topic, key, value).asDeferred().await()

// 방법 2: CompletableFuture
kafkaTemplate.send(topic, key, value).get()  // ❌ 블로킹!

// ✅ 코루틴 환경에서는 항상 방법 1 사용
```

---

## 5. Consumer (구독)

### 기본 설정

```yaml
spring:
  kafka:
    consumer:
      group-id: ${spring.application.name}
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: earliest    # 새 그룹은 처음부터 읽기
      enable-auto-commit: true       # 오프셋 자동 커밋
```

### @KafkaListener 옵션

```kotlin
@KafkaListener(
    topics = ["topic1", "topic2"],     // 여러 토픽 구독 가능
    groupId = "my-group",              // 소비자 그룹
    concurrency = "3",                 // Consumer 스레드 수 (파티션 수 이하)
    containerFactory = "myFactory"     // 커스텀 리스너 컨테이너
)
fun handle(record: ConsumerRecord<String, String>) { ... }
```

### ConsumerRecord에서 얻을 수 있는 정보

```kotlin
fun handle(record: ConsumerRecord<String, String>) {
    val topic = record.topic()       // 토픽 이름
    val partition = record.partition() // 파티션 번호
    val offset = record.offset()     // 오프셋 (읽은 위치)
    val key = record.key()           // 메시지 키
    val value = record.value()       // 메시지 값 (JSON)
    val timestamp = record.timestamp() // 발행 시각
}
```

---

## 6. 에러 처리와 DLQ

### 기본 에러 처리

메시지 처리 중 예외가 발생하면 재시도 후 DLQ로 이동.

```kotlin
@Configuration
class KafkaErrorConfig {

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.consumerFactory = consumerFactory
        factory.setCommonErrorHandler(
            DefaultErrorHandler(
                DeadLetterPublishingRecoverer(kafkaTemplate),  // DLQ로 보냄
                FixedBackOff(1000, 3)  // 1초 간격, 3번 재시도
            )
        )
        return factory
    }
}
```

### DLQ (Dead Letter Queue) 흐름

```
1. 메시지 수신
2. 처리 시도 → 실패
3. 1초 후 재시도 → 실패
4. 1초 후 재시도 → 실패
5. 1초 후 재시도 → 실패 (3회 초과)
6. DLQ 토픽으로 이동: flashsale.order.placed.dlq
7. 나중에 수동으로 확인 후 재처리
```

---

## 7. 이 프로젝트에서의 활용

### 토픽명 중앙 관리

```kotlin
// common/infrastructure/.../kafka/KafkaTopics.kt
object KafkaTopics {
    object Order {
        const val PLACED = "flashsale.order.placed"
        const val CANCELLED = "flashsale.order.cancelled"
        const val COMPLETED = "flashsale.order.completed"
    }
    object Payment {
        const val REQUESTED = "flashsale.payment.requested"
        const val COMPLETED = "flashsale.payment.completed"
        const val FAILED = "flashsale.payment.failed"
    }
}
```

### 의존성

```kotlin
// common/infrastructure/build.gradle.kts
api("org.springframework.kafka:spring-kafka")
```

---

## 8. 자주 하는 실수 / 주의사항

### Consumer 처리 중 예외를 삼키면 안 됨

```kotlin
// ❌ 예외를 삼키면 실패한 메시지가 처리된 것으로 간주됨
@KafkaListener(topics = ["order.placed"])
fun handle(record: ConsumerRecord<String, String>) {
    try {
        processOrder(record.value())
    } catch (e: Exception) {
        logger.error { "실패: ${e.message}" }  // 로그만 찍고 넘어감
    }
}

// ✅ 예외를 던져서 재시도/DLQ 처리
@KafkaListener(topics = ["order.placed"])
fun handle(record: ConsumerRecord<String, String>) {
    processOrder(record.value())  // 실패하면 예외 전파 → 재시도 → DLQ
}
```

### 키 없이 전송하면 순서 보장 안 됨

```kotlin
// ❌ 키 없음 → 라운드 로빈 → 순서 무작위
kafkaTemplate.send("order.placed", eventJson)

// ✅ orderId를 키로 → 같은 주문 이벤트는 같은 파티션 → 순서 보장
kafkaTemplate.send("order.placed", orderId, eventJson)
```

### 멱등성 미보장

```
// ❌ 같은 메시지가 2번 전달되면 2번 처리됨 (네트워크 오류 등)

// ✅ 멱등성 체크: 이미 처리된 이벤트인지 확인
if (alreadyProcessed(event.eventId)) return
processEvent(event)
markAsProcessed(event.eventId)
```

---

## 9. 정리 / 한눈에 보기

### 핵심 구성 요소

| 구성 요소 | 역할 |
|----------|------|
| `KafkaTemplate` | 메시지 발행 (Producer) |
| `@KafkaListener` | 메시지 수신 (Consumer) |
| `ConsumerRecord` | 수신된 메시지 정보 |
| `DefaultErrorHandler` | 에러 시 재시도 + DLQ |

### 설정 치트시트

| 설정 | 권장 값 | 이유 |
|------|--------|------|
| `acks` | `all` | 메시지 유실 방지 |
| `auto-offset-reset` | `earliest` | 새 Consumer Group이 처음부터 읽기 |
| `retries` | 3 | 일시적 오류 재시도 |

---

## 10. 더 알아보기

- [Spring Kafka 공식 문서](https://docs.spring.io/spring-kafka/docs/current/reference/html/)
- [Apache Kafka 공식 문서](https://kafka.apache.org/documentation/)
