# Saga 패턴으로 분산 트랜잭션 구현 가이드

> "주문 → 재고 차감 → 결제 → 완료" 플로우를 예제로 Step-by-Step 따라하기

---

## 목차

1. [왜 Saga가 필요한가?](#1-왜-saga가-필요한가)
2. [전체 흐름도](#2-전체-흐름도)
3. [Step 1: Saga 상태 정의](#step-1-saga-상태-정의)
4. [Step 2: Saga Orchestrator 구현](#step-2-saga-orchestrator-구현)
5. [Step 3: 보상 트랜잭션 구현](#step-3-보상-트랜잭션-구현)
6. [Step 4: 이벤트 발행](#step-4-이벤트-발행)
7. [Step 5: 멱등성 보장](#step-5-멱등성-보장)
8. [Step 6: 테스트](#step-6-테스트)
9. [자주 하는 실수](#자주-하는-실수)

---

## 1. 왜 Saga가 필요한가?

### 단일 DB에서는 트랜잭션이 간단하다

```kotlin
// 하나의 DB에서 모든 작업을 하면:
@Transactional
fun placeOrder() {
    orderRepository.save(order)     // 주문 저장
    stockRepository.decrement(...)  // 재고 차감
    paymentRepository.save(...)     // 결제 기록
    // → 하나라도 실패하면 전체 롤백
}
```

### 마이크로서비스에서는 DB가 분리되어 있다

```
order-service   → PostgreSQL (주문 DB)
payment-service → PostgreSQL (결제 DB)
재고            → Redis

@Transactional이 3개 시스템에 걸쳐 동작할 수 없다!
```

### Saga 패턴: "일단 실행하고, 실패하면 되돌린다"

```
정상 흐름:
  재고 차감 (성공) → 결제 요청 (성공) → 주문 완료 ✅

실패 흐름 (결제 실패):
  재고 차감 (성공) → 결제 요청 (실패) → 재고 복원 (보상) → 주문 취소 ✅
```

---

## 2. 전체 흐름도

### 정상 흐름

```
order-service                           payment-service
┌────────────────────────────────┐     ┌────────────────────┐
│ 1. 주문 생성 (STARTED)          │     │                    │
│ 2. 재고 차감 (STOCK_DECREMENTED)│     │                    │
│ 3. Kafka: order.placed ────────┼────▶│ 4. 결제 처리        │
│                                │     │ 5. Kafka: payment   │
│ 6. 주문 완료 (COMPLETED) ◀─────┼─────│    .completed       │
└────────────────────────────────┘     └────────────────────┘
```

### 실패 흐름 (결제 실패)

```
order-service                           payment-service
┌────────────────────────────────┐     ┌────────────────────┐
│ 1. 주문 생성 (STARTED)          │     │                    │
│ 2. 재고 차감 (STOCK_DECREMENTED)│     │                    │
│ 3. Kafka: order.placed ────────┼────▶│ 4. 결제 시도 → 실패  │
│                                │     │ 5. Kafka: payment   │
│ 6. 보상 시작 (COMPENSATING)     │◀────┼────.failed          │
│ 7. 재고 복원 ← 보상 트랜잭션     │     │                    │
│ 8. 주문 취소 (COMPENSATED)      │     │                    │
└────────────────────────────────┘     └────────────────────┘
```

---

## Step 1: Saga 상태 정의

```kotlin
package com.flashsale.order.domain.model

/** 주문 Saga의 상태 전이 */
enum class OrderSagaStatus {
    // === 정상 흐름 ===
    STARTED,              // 주문 생성됨
    STOCK_DECREMENTED,    // 재고 차감됨
    PAYMENT_REQUESTED,    // 결제 요청됨
    COMPLETED,            // 주문 완료

    // === 보상 흐름 ===
    COMPENSATING,         // 보상 트랜잭션 진행 중
    COMPENSATED,          // 보상 완료 (주문 취소됨)
}
```

### 상태 전이 다이어그램

```
STARTED
  ↓ (재고 차감 성공)
STOCK_DECREMENTED
  ↓ (결제 요청)
PAYMENT_REQUESTED
  ├── (결제 성공) → COMPLETED
  └── (결제 실패) → COMPENSATING
                       ↓ (재고 복원)
                    COMPENSATED
```

---

## Step 2: Saga Orchestrator 구현

> Orchestration 방식: 하나의 Orchestrator가 전체 Saga를 관리한다.

```kotlin
package com.flashsale.order.application.service

import com.flashsale.common.config.TimeoutProperties
import com.flashsale.common.domain.Result
import com.flashsale.common.logging.Log
import com.flashsale.common.logging.MdcKeys
import com.flashsale.common.logging.withMdc
import com.flashsale.order.application.port.out.EventPublisherPort
import com.flashsale.order.application.port.out.OrderPersistencePort
import com.flashsale.order.application.port.out.StockPort
import com.flashsale.order.domain.error.OrderError
import com.flashsale.order.domain.model.Order
import com.flashsale.order.domain.model.OrderSagaStatus
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service

@Service
class OrderSagaOrchestrator(
    private val stockPort: StockPort,
    private val orderPersistencePort: OrderPersistencePort,
    private val eventPublisherPort: EventPublisherPort,
    private val timeouts: TimeoutProperties,
) {
    companion object : Log

    /**
     * 주문 Saga 실행.
     * 각 단계가 실패하면 이전 단계를 보상(되돌리기)한다.
     */
    suspend fun executeSaga(
        userId: String,
        productId: String,
        quantity: Int,
    ): Result<Order, OrderError> =
        withMdc(MdcKeys.USER_ID, userId, MdcKeys.PRODUCT_ID, productId) {

            // === Step 1: 주문 생성 ===
            val order = Order.create(userId, productId, quantity)
            orderPersistencePort.save(order)
            updateSagaStatus(order.id, OrderSagaStatus.STARTED)
            logger.info { "Saga 시작: orderId=${order.id}" }

            // === Step 2: 재고 차감 ===
            val stockResult = withTimeout(timeouts.redisLuaScript) {
                stockPort.decrement(productId, quantity)
            }

            if (stockResult < 0) {
                logger.warn { "재고 부족: productId=$productId" }
                updateSagaStatus(order.id, OrderSagaStatus.COMPENSATED)
                return@withMdc Result.failure(
                    OrderError.InsufficientStock(available = 0, requested = quantity),
                )
            }
            updateSagaStatus(order.id, OrderSagaStatus.STOCK_DECREMENTED)
            logger.info { "재고 차감 완료: remaining=$stockResult" }

            // === Step 3: 결제 요청 (Kafka 이벤트) ===
            try {
                withTimeout(timeouts.kafkaProduce) {
                    eventPublisherPort.publishOrderPlaced(order)
                }
                updateSagaStatus(order.id, OrderSagaStatus.PAYMENT_REQUESTED)
                logger.info { "결제 요청 이벤트 발행: orderId=${order.id}" }
            } catch (e: Exception) {
                // 이벤트 발행 실패 → 재고 복원 (보상)
                logger.error(e) { "이벤트 발행 실패, 보상 시작: orderId=${order.id}" }
                compensate(order.id, productId, quantity)
                return@withMdc Result.failure(OrderError.LockAcquisitionFailed(productId))
            }

            Result.success(order)
        }

    /**
     * 결제 완료 이벤트 수신 시 호출.
     * Kafka Consumer에서 호출된다.
     */
    suspend fun handlePaymentCompleted(orderId: String) {
        updateSagaStatus(orderId, OrderSagaStatus.COMPLETED)
        logger.info { "주문 완료: orderId=$orderId" }
    }

    /**
     * 결제 실패 이벤트 수신 시 보상 트랜잭션 실행.
     */
    suspend fun handlePaymentFailed(orderId: String, productId: String, quantity: Int) {
        logger.warn { "결제 실패, 보상 시작: orderId=$orderId" }
        compensate(orderId, productId, quantity)
    }

    /** 보상 트랜잭션: 이전 단계를 되돌린다 */
    private suspend fun compensate(orderId: String, productId: String, quantity: Int) {
        updateSagaStatus(orderId, OrderSagaStatus.COMPENSATING)

        try {
            // 재고 복원
            withTimeout(timeouts.redisLuaScript) {
                stockPort.restore(productId, quantity)
            }
            logger.info { "재고 복원 완료: productId=$productId, qty=$quantity" }
        } catch (e: Exception) {
            logger.error(e) { "재고 복원 실패! 수동 처리 필요: orderId=$orderId" }
            // 보상도 실패하면 로그 + 알림으로 수동 처리
        }

        // 주문 상태를 취소로 변경
        orderPersistencePort.updateStatus(
            orderId,
            com.flashsale.order.domain.model.OrderStatus.CANCELLED,
        )
        updateSagaStatus(orderId, OrderSagaStatus.COMPENSATED)
        logger.info { "보상 완료, 주문 취소: orderId=$orderId" }
    }

    private suspend fun updateSagaStatus(orderId: String, status: OrderSagaStatus) {
        // Saga 상태를 DB 또는 Redis에 저장 (감사 추적용)
        logger.debug { "Saga 상태 변경: orderId=$orderId, status=$status" }
    }
}
```

---

## Step 3: 보상 트랜잭션 구현

### StockPort에 restore 메서드 추가

```kotlin
interface StockPort {
    suspend fun getRemaining(productId: String): Int?
    suspend fun decrement(productId: String, quantity: Int): Long
    suspend fun restore(productId: String, quantity: Int)  // ← 보상용 추가
}
```

### Redis Adapter에 구현

```kotlin
@Component
class RedisStockAdapter(
    private val redisTemplate: ReactiveStringRedisTemplate,
) : StockPort {
    // ... 기존 코드 ...

    /** 재고 복원 (보상 트랜잭션) */
    override suspend fun restore(productId: String, quantity: Int) {
        redisTemplate.opsForValue()
            .increment(RedisKeys.Stock.remaining(productId), quantity.toLong())
            .awaitSingle()
    }
}
```

---

## Step 4: 이벤트 발행

### EventPublisherPort

```kotlin
interface EventPublisherPort {
    suspend fun publishOrderPlaced(order: Order)
    suspend fun publishOrderCancelled(orderId: String, reason: String)
}
```

### Kafka 구현

```kotlin
@Component
class KafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) : EventPublisherPort {

    override suspend fun publishOrderPlaced(order: Order) {
        val event = OrderPlacedEvent(
            aggregateId = order.id,
            userId = order.userId,
            productId = order.productId,
            quantity = order.quantity,
        )
        send(KafkaTopics.Order.PLACED, order.id, event)
    }

    override suspend fun publishOrderCancelled(orderId: String, reason: String) {
        val event = OrderCancelledEvent(
            aggregateId = orderId,
            reason = reason,
        )
        send(KafkaTopics.Order.CANCELLED, orderId, event)
    }

    private suspend fun send(topic: String, key: String, event: Any) {
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(topic, key, json)
            .asDeferred()
            .await()
    }
}
```

---

## Step 5: 멱등성 보장

### 왜 필요한가?

```
Kafka가 같은 메시지를 2번 전달할 수 있다 (at-least-once):
  payment.failed → 보상 실행 (재고 +1)
  payment.failed → 보상 실행 (재고 +1)  ← 중복! 재고가 2개 복원됨!
```

### eventId 기반 중복 체크

```kotlin
@Component
class PaymentResultConsumer(
    private val orchestrator: OrderSagaOrchestrator,
    private val idempotencyChecker: IdempotencyChecker,
    private val objectMapper: ObjectMapper,
) {
    companion object : Log

    @KafkaListener(topics = [KafkaTopics.Payment.FAILED])
    suspend fun handlePaymentFailed(record: ConsumerRecord<String, String>) {
        val event = objectMapper.readValue<PaymentFailedEventDto>(record.value())

        // 멱등성 체크
        if (idempotencyChecker.isDuplicate(event.eventId)) {
            logger.warn { "중복 이벤트 무시: ${event.eventId}" }
            return
        }

        orchestrator.handlePaymentFailed(
            orderId = event.orderId,
            productId = event.productId,
            quantity = event.quantity,
        )

        idempotencyChecker.markProcessed(event.eventId)
    }
}
```

---

## Step 6: 테스트

### 정상 흐름 테스트

```kotlin
class OrderSagaOrchestratorTest : FunSpec({
    val stockPort = mockk<StockPort>()
    val orderPersistencePort = mockk<OrderPersistencePort>()
    val eventPublisherPort = mockk<EventPublisherPort>()
    val timeouts = TimeoutProperties()
    val orchestrator = OrderSagaOrchestrator(
        stockPort, orderPersistencePort, eventPublisherPort, timeouts,
    )

    test("재고 충분 + 이벤트 발행 성공 → 주문 성공") {
        coEvery { stockPort.decrement("prod-1", 1) } returns 99
        coEvery { orderPersistencePort.save(any()) } answers { firstArg() }
        coEvery { eventPublisherPort.publishOrderPlaced(any()) } just runs

        val result = orchestrator.executeSaga("user-1", "prod-1", 1)

        result.isSuccess shouldBe true
        coVerify(exactly = 1) { eventPublisherPort.publishOrderPlaced(any()) }
    }
})
```

### 보상 흐름 테스트

```kotlin
test("이벤트 발행 실패 → 재고 복원 (보상)") {
    coEvery { stockPort.decrement("prod-1", 1) } returns 99
    coEvery { orderPersistencePort.save(any()) } answers { firstArg() }
    coEvery { eventPublisherPort.publishOrderPlaced(any()) } throws IOException("Kafka 장애")
    coEvery { stockPort.restore("prod-1", 1) } just runs
    coEvery { orderPersistencePort.updateStatus(any(), any()) } just runs

    val result = orchestrator.executeSaga("user-1", "prod-1", 1)

    result.isFailure shouldBe true
    coVerify(exactly = 1) { stockPort.restore("prod-1", 1) }  // 재고 복원 확인
}

test("결제 실패 이벤트 수신 → 재고 복원 + 주문 취소") {
    coEvery { stockPort.restore("prod-1", 2) } just runs
    coEvery { orderPersistencePort.updateStatus("order-1", OrderStatus.CANCELLED) } just runs

    orchestrator.handlePaymentFailed("order-1", "prod-1", 2)

    coVerify(exactly = 1) { stockPort.restore("prod-1", 2) }
    coVerify(exactly = 1) {
        orderPersistencePort.updateStatus("order-1", OrderStatus.CANCELLED)
    }
}
```

---

## 자주 하는 실수

### 1. 보상 트랜잭션 누락

```
❌ 재고를 차감했는데, 결제 실패 시 재고를 복원하지 않음
→ 1,000개 중 실제 900개만 팔렸는데 재고가 0으로 표시

✅ 모든 부작용(side effect)에 대해 보상 로직을 정의
   재고 차감 → 보상: 재고 복원
   결제 승인 → 보상: 결제 취소
```

### 2. 보상도 실패하는 경우

```
❌ 보상 실패를 무시
→ 재고가 영원히 안 돌아옴

✅ 보상 실패 시:
   1. 에러 로그 (ERROR 레벨)
   2. 알림 발송 (운영팀에 수동 처리 요청)
   3. DLQ에 실패 이벤트 보관
```

### 3. 멱등성 미보장

```
❌ 같은 결제 실패 이벤트가 2번 오면 재고가 2번 복원됨

✅ eventId로 중복 체크 후 처리
```

### 4. Saga 상태 추적 부재

```
❌ 중간에 어떤 상태인지 알 수 없음
→ 장애 발생 시 디버깅 불가

✅ 모든 상태 전이를 기록 (DB 또는 로그)
   orderId=order-1, STARTED → STOCK_DECREMENTED → PAYMENT_REQUESTED → COMPENSATING → COMPENSATED
```
