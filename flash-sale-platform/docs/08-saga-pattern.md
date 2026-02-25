# 8. Saga 패턴 (분산 트랜잭션)

> **한 줄 요약**: 여러 마이크로서비스에 걸친 작업을 일관되게 처리하기 위한 보상 트랜잭션 패턴

---

## 왜 필요한가?

### 단일 DB 트랜잭션의 한계

모놀리식 시스템에서는 하나의 DB 트랜잭션으로 모든 것을 처리할 수 있었습니다.

```kotlin
// 모놀리식: 하나의 트랜잭션으로 모든 작업 처리
@Transactional
fun placeOrder(request: OrderRequest) {
    stockRepository.decrement(request.productId, 1)   // 재고 차감
    val order = orderRepository.save(Order.create(request)) // 주문 생성
    paymentRepository.save(Payment.create(order))           // 결제 생성
    // 하나라도 실패하면 → 전부 롤백 (DB가 보장)
}
```

### 마이크로서비스에서의 문제

서비스마다 별도의 DB를 사용하므로, 하나의 트랜잭션으로 묶을 수 없습니다.

```
[주문 서비스 / Redis]  →  [결제 서비스 / PostgreSQL]
     재고 차감                   결제 처리

만약 재고 차감 성공 → 결제 실패하면?
→ 재고는 이미 차감됨 → 복원해야 함!
→ 하지만 다른 DB/서비스라서 자동 롤백 불가!
```

---

## Saga 패턴이란?

여러 서비스에 걸친 작업을 **순차적인 로컬 트랜잭션 + 보상 트랜잭션**으로 관리합니다.

### 성공 시나리오

```
Step 1: 재고 차감 (Redis)         ✅ 성공
Step 2: 주문 생성 (PostgreSQL)    ✅ 성공
Step 3: 결제 요청 (외부 API)      ✅ 성공
Step 4: 주문 확정 (PostgreSQL)    ✅ 성공
→ 완료!
```

### 실패 시나리오 (보상 트랜잭션)

```
Step 1: 재고 차감 (Redis)         ✅ 성공
Step 2: 주문 생성 (PostgreSQL)    ✅ 성공
Step 3: 결제 요청 (외부 API)      ❌ 실패!
→ 보상 트랜잭션 시작 (역순으로 되돌림)
Step 2 보상: 주문 취소 (CANCELLED로 변경)
Step 1 보상: 재고 복원 (Redis INCRBY)
→ 일관된 상태로 복구!
```

---

## 두 가지 방식

### 1. Choreography (코레오그래피) - 이벤트 기반

각 서비스가 **이벤트를 발행하고 구독**하여 자율적으로 처리합니다.

```
주문서비스 → [order.placed] → 결제서비스 → [payment.completed] → 주문서비스 → [order.completed]
                              결제서비스 → [payment.failed]    → 주문서비스 → 재고 복원
```

- 장점: 서비스 간 느슨한 결합
- 단점: 흐름 추적이 어려움 (이벤트가 여기저기 흩어짐)

### 2. Orchestration (오케스트레이션) - 중앙 조율자

**Saga Orchestrator**가 전체 흐름을 조율합니다.

```
[Saga Orchestrator]
├── Step 1: stockService.decrement()    → 성공
├── Step 2: orderService.create()       → 성공
├── Step 3: paymentService.process()    → 실패!
├── Compensate 2: orderService.cancel()
└── Compensate 1: stockService.restore()
```

- 장점: 흐름이 한 곳에서 명확하게 보임
- 단점: Orchestrator가 단일 장애점이 될 수 있음

---

## 이 프로젝트에서의 Saga 구현

이 프로젝트는 **Choreography 방식** (Kafka 이벤트 기반)을 사용합니다.

### 전체 흐름

```
1. 사용자가 주문 요청
   └── [주문 서비스]
       ├── Redis: 재고 차감 (Lua Script)
       ├── DB: 주문 생성 (status: PENDING)
       └── Kafka: "order.placed" 이벤트 발행

2. 결제 서비스가 이벤트 수신
   └── [결제 서비스]
       ├── 외부 결제 API 호출
       ├── 성공 시: Kafka "payment.completed" 발행
       └── 실패 시: Kafka "payment.failed" 발행

3-A. 결제 성공
   └── [주문 서비스]
       ├── DB: 주문 상태 → COMPLETED
       └── Kafka: "order.completed" 발행 → [알림 서비스]

3-B. 결제 실패 (보상 트랜잭션)
   └── [주문 서비스]
       ├── DB: 주문 상태 → CANCELLED
       ├── Redis: 재고 복원 (INCRBY)
       ├── Kafka: "stock.restored" 발행
       └── Kafka: "order.cancelled" 발행 → [알림 서비스]
```

### 코드 예시

```kotlin
// 결제 서비스: 결제 실패 시 이벤트 발행
@KafkaListener(topics = [KafkaTopics.Order.PLACED])
suspend fun handleOrderPlaced(event: OrderPlacedEvent) {
    try {
        val paymentResult = paymentGateway.requestPayment(
            PaymentRequest(orderId = event.orderId, amount = event.amount)
        )
        // 성공: payment.completed 이벤트 발행
        kafkaPublisher.publish(
            KafkaTopics.Payment.COMPLETED,
            PaymentCompletedEvent(event.orderId, paymentResult.transactionId)
        )
    } catch (ex: Exception) {
        // 실패: payment.failed 이벤트 발행 → 보상 트랜잭션 트리거
        kafkaPublisher.publish(
            KafkaTopics.Payment.FAILED,
            PaymentFailedEvent(event.orderId, ex.message ?: "Unknown error")
        )
    }
}
```

```kotlin
// 주문 서비스: 결제 실패 이벤트 수신 → 보상 트랜잭션 실행
@KafkaListener(topics = [KafkaTopics.Payment.FAILED])
suspend fun handlePaymentFailed(event: PaymentFailedEvent) {
    // 1. 주문 취소
    orderRepository.updateStatus(event.orderId, OrderStatus.CANCELLED)

    // 2. 재고 복원 (보상)
    val order = orderRepository.findById(event.orderId)
    stockPort.restore(order.productId, order.quantity)

    // 3. 재고 복원 이벤트 발행
    kafkaPublisher.publish(
        KafkaTopics.Stock.RESTORED,
        StockRestoredEvent(order.productId, order.quantity)
    )

    // 4. 주문 취소 알림 요청
    kafkaPublisher.publish(
        KafkaTopics.Order.CANCELLED,
        OrderCancelledEvent(event.orderId, event.reason)
    )
}
```

---

## Saga에서 멱등성이 중요한 이유

Kafka 메시지가 중복 전달될 수 있으므로, 보상 트랜잭션도 멱등해야 합니다.

```kotlin
// ❌ 멱등하지 않은 재고 복원
suspend fun restore(productId: String, quantity: Int) {
    redisTemplate.opsForValue().increment(RedisKeys.Stock.remaining(productId), quantity)
    // 메시지 2번 전달되면 → 재고가 2번 복원됨!
}

// ✅ 멱등한 재고 복원
suspend fun restore(productId: String, quantity: Int, orderId: String) {
    // 이미 복원된 주문인지 확인
    val key = "stock:restored:$orderId"
    val isFirst = redisTemplate.opsForValue()
        .setIfAbsent(key, "1", Duration.ofHours(24))
        .awaitSingle()

    if (isFirst) {
        redisTemplate.opsForValue().increment(RedisKeys.Stock.remaining(productId), quantity)
    }
    // 이미 복원됐으면 무시
}
```

---

## Saga vs 2PC (Two-Phase Commit)

| | Saga | 2PC |
|---|---|---|
| 일관성 | 최종적 일관성 (Eventually Consistent) | 강한 일관성 |
| 성능 | 빠름 (각 서비스가 독립적으로 커밋) | 느림 (모든 참여자가 준비될 때까지 대기) |
| 가용성 | 높음 (부분 실패 허용) | 낮음 (코디네이터 장애 시 전체 중단) |
| 복잡성 | 보상 로직 필요 | 프로토콜 자체가 복잡 |
| 적합한 환경 | 마이크로서비스 | 단일 DB 또는 소수의 DB |

→ 마이크로서비스 환경에서는 **Saga 패턴**이 사실상 표준

---

## 상태 다이어그램

```
주문 상태:
  [PENDING] ──결제 성공──▶ [COMPLETED]
     │
     └────── 결제 실패 ──▶ [CANCELLED] (+ 재고 복원)

결제 상태:
  [REQUESTED] ──API 성공──▶ [COMPLETED]
     │
     └──────── API 실패 ──▶ [FAILED]
```

---

## 더 알아보기

- **Saga 패턴 원문**: Chris Richardson의 [Microservices Patterns](https://microservices.io/patterns/data/saga.html)
- **이 프로젝트 토픽**: `common/infrastructure/src/.../kafka/KafkaTopics.kt`
- **보상 트랜잭션 구현**: `payment-service`와 `order-service`의 Kafka Consumer
