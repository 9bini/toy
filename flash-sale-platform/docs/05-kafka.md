# 5. Apache Kafka (이벤트 스트리밍)

> **한 줄 요약**: 서비스 간 비동기 메시지를 안정적으로 전달하는 분산 이벤트 스트리밍 플랫폼

---

## 왜 Kafka가 필요한가?

### 직접 호출의 문제

```
주문 서비스 → 결제 서비스  (HTTP 직접 호출)
              ↓ 결제 서비스 다운!
주문 서비스도 실패 → 사용자에게 에러
```

- 결제 서비스가 죽으면 → 주문도 실패
- 서비스 간 강한 결합 → 하나의 장애가 전체로 전파

### Kafka를 통한 비동기 통신

```
주문 서비스 → [Kafka 토픽] → 결제 서비스
                 ↓ 결제 서비스 다운!
            메시지가 Kafka에 보관됨
                 ↓ 결제 서비스 복구!
            보관된 메시지를 처리 → 정상 동작
```

- 결제 서비스가 죽어도 → 주문은 성공 (메시지가 Kafka에 안전하게 보관)
- 서비스 간 느슨한 결합 → 장애 전파 방지
- 복구 후 밀린 메시지를 순서대로 처리

---

## 핵심 개념

### 1. Topic (토픽) = 메시지 카테고리

```
flashsale.order.placed     ← 주문 생성 이벤트
flashsale.order.cancelled  ← 주문 취소 이벤트
flashsale.payment.completed ← 결제 완료 이벤트
flashsale.payment.failed    ← 결제 실패 이벤트
```

### 2. Producer (생산자) = 메시지를 보내는 쪽

```kotlin
// 주문 서비스가 "주문 생성" 이벤트를 Kafka에 발행
kafkaTemplate.send("flashsale.order.placed", orderId, orderEvent)
```

### 3. Consumer (소비자) = 메시지를 받는 쪽

```kotlin
// 결제 서비스가 "주문 생성" 이벤트를 구독하여 결제 처리
@KafkaListener(topics = ["flashsale.order.placed"])
fun handleOrderPlaced(event: OrderPlacedEvent) {
    paymentService.processPayment(event)
}
```

### 4. Partition (파티션) = 병렬 처리 단위

```
토픽: flashsale.order.placed
├── Partition 0: [주문A, 주문D, 주문G, ...]  → Consumer 1
├── Partition 1: [주문B, 주문E, 주문H, ...]  → Consumer 2
└── Partition 2: [주문C, 주문F, 주문I, ...]  → Consumer 3
```

- 같은 키(orderId)의 메시지는 항상 같은 파티션으로 → **순서 보장**
- 파티션 수만큼 Consumer를 늘려 **병렬 처리** 가능

### 5. Consumer Group = 소비자 그룹

```
토픽: flashsale.order.placed

Consumer Group: "payment-service"
├── payment-1 ← Partition 0
├── payment-2 ← Partition 1
└── payment-3 ← Partition 2

Consumer Group: "notification-service"
├── notification-1 ← Partition 0, 1, 2 (모든 파티션)
```

- 같은 그룹 내에서는 각 메시지를 **한 번만** 처리 (작업 분배)
- 다른 그룹은 **독립적으로** 같은 메시지를 처리 (용도별 구독)

---

## 이 프로젝트에서의 이벤트 흐름

```
                    flashsale.order.placed
[주문 서비스] ──────────────────────────────────▶ [결제 서비스]
     │                                              │
     │          flashsale.payment.completed          │
     ◀──────────────────────────────────────────────┘
     │                                              │
     │          flashsale.payment.failed             │
     ◀──────────────────────────────────────────────┘
     │
     │          flashsale.order.completed
     ├──────────────────────────────────────────────▶ [알림 서비스]
     │
     │          flashsale.stock.decremented
     ├──────────────────────────────────────────────▶ (재고 이벤트 로그)
     │
     │          flashsale.notification.send-requested
     └──────────────────────────────────────────────▶ [알림 서비스]
```

### 토픽 관리

모든 토픽명은 `KafkaTopics` 오브젝트에서 중앙 관리합니다.

```kotlin
// common/infrastructure/src/.../kafka/KafkaTopics.kt
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
    object Stock {
        const val DECREMENTED = "flashsale.stock.decremented"
        const val RESTORED = "flashsale.stock.restored"
    }
    // DLQ: 처리 실패 메시지 보관
    fun dlq(originalTopic: String) = "$originalTopic.dlq"
}
```

**토픽 네이밍 규칙**: `flashsale.{도메인}.{이벤트}`

---

## KRaft 모드

이 프로젝트의 Kafka는 **KRaft 모드**로 실행됩니다.

### 기존 방식 (ZooKeeper)
```
Kafka Broker ←→ ZooKeeper (별도 프로세스)
```
- ZooKeeper가 Kafka의 메타데이터를 관리
- 별도 프로세스를 운영해야 하는 부담

### KRaft 모드 (이 프로젝트)
```
Kafka Broker (자체 메타데이터 관리)
```
- ZooKeeper 불필요 → 운영 단순화
- Kafka 3.3+ 부터 프로덕션 지원
- `docker-compose.yml`에서 `KAFKA_PROCESS_ROLES: broker,controller`로 설정

---

## 멱등성 (Idempotency)

**문제**: 네트워크 오류로 같은 메시지가 2번 전달되면?

```
Consumer가 "주문 A 결제" 메시지를 받고 처리 완료
→ ack 전송 중 네트워크 오류
→ Kafka가 "주문 A 결제" 메시지를 다시 전달
→ 결제가 2번 처리됨! (이중 결제)
```

**해결**: 멱등성 키로 중복 처리 방지

```kotlin
@KafkaListener(topics = [KafkaTopics.Payment.REQUESTED])
suspend fun handlePaymentRequest(event: PaymentRequestedEvent) {
    // 멱등성 키: 이미 처리된 메시지인지 확인
    val idempotencyKey = RedisKeys.Order.idempotencyKey(event.orderId)
    val alreadyProcessed = redisTemplate.opsForValue()
        .setIfAbsent(idempotencyKey, "processed", Duration.ofHours(24))
        .awaitSingle()

    if (!alreadyProcessed) {
        // 이미 처리됨 → 무시
        return
    }

    // 처음 처리하는 메시지 → 결제 실행
    paymentService.process(event)
}
```

---

## DLQ (Dead Letter Queue)

처리에 실패한 메시지를 별도 토픽에 보관하여 나중에 재처리하거나 분석합니다.

```
flashsale.order.placed
├── 정상 메시지 → 처리 완료
├── 정상 메시지 → 처리 완료
└── 오류 메시지 → 3회 재시도 실패 → flashsale.order.placed.dlq (DLQ 토픽으로 이동)
```

---

## Kafka HA (고가용성)

### 개발 환경 (docker-compose.yml)
- 1개 브로커, replication-factor=1
- 데이터 유실 가능 (개발이니까 OK)

### 운영 환경 (docker-compose.ha.yml)
- 3개 브로커, replication-factor=3, min.insync.replicas=2

```
Broker 1: [Partition 0 Leader] [Partition 1 Follower]
Broker 2: [Partition 0 Follower] [Partition 1 Leader]
Broker 3: [Partition 0 Follower] [Partition 1 Follower]
```

- 1개 브로커가 죽어도 → Follower가 Leader로 승격 → 서비스 지속
- min.insync.replicas=2 → 최소 2개 복제본이 확인해야 "쓰기 성공"

---

## Producer vs Consumer 정리

| | Producer (발행) | Consumer (구독) |
|---|---|---|
| 역할 | 메시지를 토픽에 보냄 | 토픽에서 메시지를 읽음 |
| 이 프로젝트 | 주문/결제 이벤트 발행 | 이벤트를 받아 처리 |
| 핵심 설정 | `acks=all` (안전한 쓰기) | `auto.offset.reset=earliest` |
| 실패 처리 | 재시도 (Kafka RetryTemplate) | DLQ로 이동 |

---

## 더 알아보기

- **Kafka 공식 문서**: [kafka.apache.org/documentation](https://kafka.apache.org/documentation/)
- **이 프로젝트 관련 파일**: `common/infrastructure/src/.../kafka/KafkaTopics.kt`
- **HA 설정**: `docker-compose.ha.yml`의 Kafka 섹션
