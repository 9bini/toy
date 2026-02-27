---
name: kafka-setup
description: Kafka Producer/Consumer를 설정합니다. 토픽 생성, 직렬화, exactly-once, DLQ 설정을 포함합니다.
argument-hint: [topic-name] [producer|consumer]
---

$ARGUMENTS Kafka 구성을 설정하세요.

## 설정 항목

### 1. 토픽 정의
- 토픽명: `flashsale.{domain}.{event}` (예: `flashsale.order.confirmed`)
- 토픽 상수: `KafkaTopics` object에 중앙 관리
- 파티션 수: 서비스 인스턴스 수 기반 결정
- 복제 팩터: 개발환경 1, 운영 3
- 보존 기간: 이벤트별 설정

### 2. Producer 설정
```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all                    # 모든 replica 확인 (가장 안전)
      properties:
        enable.idempotence: true   # Producer 수준 중복 방지
        max.in.flight.requests.per.connection: 5  # idempotence=true일 때 5 이하 필수
```

**Producer 핵심 규칙:**
```kotlin
// ✅ 반드시 key를 포함하여 전송 — 순서 보장
kafkaTemplate.send(topic, key, json).asDeferred().await()

// ❌ .get() 절대 금지 (blocking)
kafkaTemplate.send(topic, key, json).get()

// key = aggregate ID (예: orderId) → 같은 주문의 이벤트는 같은 파티션
```

### 3. Consumer 설정
```yaml
spring:
  kafka:
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false    # 수동 offset 관리 → exactly-once에 가깝게
      properties:
        isolation.level: read_committed
```

**Consumer 핵심 규칙:**
```kotlin
@KafkaListener(topics = ["flashsale.order.placed"], groupId = "payment-service")
fun handle(record: ConsumerRecord<String, String>) {
    val event = objectMapper.readValue<OrderPlacedEvent>(record.value())
    // ✅ 멱등성 검사 필수
    if (isProcessed(event.eventId)) return
    processOrder(event)
    markProcessed(event.eventId)
}

// ❌ 예외를 삼키지 말 것 — catch로 잡고 로그만 찍으면 DLQ/재시도 동작 안 함
```
- Consumer group 이름 = 서비스 이름 (서비스별 독립 소비)

### 4. DLQ (Dead Letter Queue)
```
처리 실패 → 1초 대기 → 재시도 1회
         → 1초 대기 → 재시도 2회
         → 1초 대기 → 재시도 3회 실패
         → {topic}.dlq 로 이동
```
- `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 조합
- 재시도 불가능한 에러(역직렬화 실패 등)는 `addNotRetryableExceptions`로 즉시 DLQ

### 5. 멱등성 처리
- Kafka는 중복 전달이 발생함 (at-least-once)
- 멱등성 키 = `DomainEvent.eventId`
- 처리 여부를 Redis 또는 DB에 기록하여 중복 차단

### 6. 코드 위치
- Config: `{service}/src/main/kotlin/.../config/KafkaConfig.kt`
- Producer: `{service}/src/main/kotlin/.../adapter/out/kafka/`
- Consumer: `{service}/src/main/kotlin/.../adapter/in/kafka/`
- 토픽 상수: `common/infrastructure/src/.../kafka/Topics.kt`

## 필수 사항
- 메시지 발행은 반드시 멱등성 키 포함
- Consumer는 멱등성 처리 (중복 메시지 안전)
- 통합 테스트는 Testcontainers Kafka 사용
- Producer는 반드시 key 포함하여 전송
- Consumer는 예외를 삼키지 않을 것 (DLQ 동작 보장)
- `.asDeferred().await()` 사용 (`.get()` 금지)
