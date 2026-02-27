# 7. Resilience (장애 대응)

> **한 줄 요약**: Spring Framework 7의 네이티브 `@Retryable` + Kotlin 코루틴 `withTimeout`으로 외부 호출 안정성을 확보한다.

---

## 왜 필요한가?

### 장애 전파의 공포

```
상황: 결제 API 서버가 느려짐 (응답 5초 → 30초)

주문 서비스 → 결제 API 호출 → 30초 대기...
           → 결제 API 호출 → 30초 대기...
           → 결제 API 호출 → 30초 대기...
           (코루틴이 모두 대기 상태)
           → 주문 서비스도 응답 불가!
           → 전체 시스템 다운!
```

**하나의 느린 외부 서비스** → **연쇄적으로 전체 시스템 마비** (Cascading Failure)

---

## 이 프로젝트의 장애 대응 전략

### 1. withTimeout (코루틴 타임아웃)

Kotlin 코루틴의 `withTimeout`으로 **모든 외부 호출에 시간 제한**을 건다.
타임아웃 초과 시 `TimeoutCancellationException`이 발생하여 즉시 실패한다.

```kotlin
@Component
class RedisStockAdapter(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val timeouts: TimeoutProperties,
) : StockPort {
    override suspend fun getRemaining(productId: String): Int =
        withTimeout(timeouts.redisOperation) {  // 100ms
            redisTemplate.opsForValue()
                .get(RedisKeys.Stock.remaining(productId))
                .awaitSingleOrNull()?.toInt() ?: 0
        }
}
```

타임아웃 값은 `TimeoutProperties`에 중앙 관리된다:

| 연산 | 기본값 | 설명 |
|------|--------|------|
| Redis 단순 연산 | 100ms | GET/SET |
| Redis Lua Script | 200ms | 원자적 스크립트 |
| Redisson 분산 락 대기 | 3,000ms | 락 획득 대기 |
| Kafka 메시지 발행 | 1,000ms | Producer 전송 |
| 외부 결제 API | 3,000ms | HTTP 호출 |
| DB 쿼리 | 2,000ms | R2DBC |
| DB 트랜잭션 | 5,000ms | 전체 트랜잭션 |
| 서비스 간 호출 | 2,000ms | 내부 HTTP |

### 2. @Retryable (Spring Framework 7 네이티브 재시도)

Spring Framework 7에 내장된 `@Retryable`로 **일시적 오류를 자동 재시도**한다.
별도의 외부 의존성이 필요 없다.

```kotlin
import org.springframework.resilience.annotation.Retryable

@Component
class ExternalPaymentAdapter(
    private val webClient: WebClient,
    private val timeouts: TimeoutProperties,
) : PaymentGatewayPort {
    companion object : Log

    @Retryable(
        maxRetries = 2,                               // 최대 2회 재시도
        delay = 1000,                                 // 1초 간격
        multiplier = 2.0,                             // 지수 백오프 (1s → 2s)
        includes = [IOException::class, TimeoutCancellationException::class],
        excludes = [IllegalArgumentException::class],  // 비즈니스 에러는 재시도 안 함
    )
    override suspend fun requestPayment(orderId: String, amount: Long): PaymentResult =
        withTimeout(timeouts.paymentApi) {
            webClient.post()
                .uri("/api/payments")
                .bodyValue(mapOf("orderId" to orderId, "amount" to amount))
                .retrieve()
                .awaitBody()
        }
}
```

#### @Retryable 주요 파라미터

| 파라미터 | 타입 | 기본값 | 설명 |
|----------|------|--------|------|
| `maxRetries` | int | 3 | 최대 재시도 횟수 |
| `delay` | long | 1000 | 기본 대기 시간 (ms) |
| `multiplier` | double | 1.0 | 지수 백오프 배수 |
| `maxDelay` | long | Long.MAX | 최대 대기 시간 상한 |
| `jitter` | long | 0 | ± 랜덤 지터 (ms) |
| `includes` | Class[] | {} | 재시도할 예외 (비어 있으면 전부) |
| `excludes` | Class[] | {} | 재시도하지 않을 예외 |
| `maxTimeout` | long | -1 | 전체 소요 시간 상한 (ms) |

#### 재시도 전략 (지수 백오프 + 지터)

```
고정 간격 (multiplier=1.0):    1000ms → 1000ms → 1000ms
지수 백오프 (multiplier=2.0):  1000ms → 2000ms → 4000ms
지수 백오프 + 지터 (jitter=200):  1000±200ms → 2000±200ms → 4000±200ms
```

### 3. @ConcurrencyLimit (동시성 제한)

Spring Framework 7에 내장된 `@ConcurrencyLimit`으로 **동시 실행 수를 제한**한다.
Virtual Thread 환경에서 특히 유용하다.

```kotlin
import org.springframework.resilience.annotation.ConcurrencyLimit

@Component
class NotificationSender {
    @ConcurrencyLimit(5)  // 최대 5건 동시 발송
    suspend fun sendPushNotification(userId: String, message: String) {
        // 외부 Push API 호출
    }
}
```

### 4. Kafka DLQ (Dead Letter Queue)

Kafka Consumer는 `KafkaErrorConfig`에서 3회 재시도 후 DLQ로 전송한다:

```
1차 처리 → 실패 → 1초 대기
2차 처리 → 실패 → 1초 대기
3차 처리 → 실패 → DLQ ({topic}.dlq)로 이동
```

---

## 활성화 설정

`@EnableResilientMethods`가 `FlashSaleCommonAutoConfiguration`에 선언되어
모든 서비스에서 자동으로 `@Retryable`, `@ConcurrencyLimit`이 활성화된다.

```kotlin
// common/infrastructure
@AutoConfiguration
@EnableResilientMethods  // Spring 네이티브 Resilience 활성화
class FlashSaleCommonAutoConfiguration
```

의존성은 `spring-boot-starter-aop`가 필요하며, `common/infrastructure`에 포함되어 있다.

---

## 이 프로젝트에서의 적용 위치

```
[주문 서비스]
    ├── Redis 호출 ← withTimeout (100ms)
    ├── Redis Lua Script ← withTimeout (200ms)
    ├── Redisson 분산 락 ← withTimeout (3s)
    ├── Kafka 발행 ← withTimeout (1s) + @Retryable
    ├── DB 쿼리 ← withTimeout (2s)
    └── 서비스 간 호출 ← withTimeout (2s) + @Retryable

[결제 서비스]
    ├── 외부 결제 API ← withTimeout (3s) + @Retryable (2회, 지수 백오프)
    ├── Kafka 발행 ← withTimeout (1s) + @Retryable
    └── DB 트랜잭션 ← withTimeout (5s)

[알림 서비스]
    ├── 외부 Push API ← withTimeout + @Retryable + @ConcurrencyLimit
    └── Kafka Consumer ← KafkaErrorConfig (3회 재시도 + DLQ)

[Gateway]
    └── Rate Limiting ← Nginx (1차, IP 기반) + Redis Token Bucket (2차, 사용자 기반)
```

---

## withTimeout + @Retryable 조합 패턴

```kotlin
class OrderUseCaseImpl(
    private val stockPort: StockPort,
    private val paymentGateway: PaymentGatewayPort,
    private val timeouts: TimeoutProperties,
) {
    suspend fun placeOrder(command: PlaceOrderCommand): Result<Order, OrderError> {
        // 1. Redis 재고 조회 — 타임아웃만 (재시도 불필요: Redis는 빠르게 성공/실패)
        val stock = withTimeout(timeouts.redisOperation) {
            stockPort.getRemaining(command.productId)
        }

        // 2. 결제 요청 — 타임아웃 + 재시도 (일시적 네트워크 오류 대비)
        // @Retryable이 paymentGateway 어댑터에 선언되어 있음
        val paymentResult = paymentGateway.requestPayment(
            orderId = command.orderId,
            amount = command.totalAmount,
        )

        return Result.success(order)
    }
}
```

---

## 자주 하는 실수

### 1. 비즈니스 에러에 재시도 적용

```kotlin
// ❌ 모든 예외 재시도 → "잔액 부족" 같은 비즈니스 에러도 재시도됨
@Retryable(maxRetries = 3)
suspend fun pay(amount: Long) { ... }

// ✅ 일시적 오류만 재시도
@Retryable(
    maxRetries = 3,
    includes = [IOException::class, TimeoutCancellationException::class],
    excludes = [InsufficientBalanceException::class],
)
suspend fun pay(amount: Long) { ... }
```

### 2. withTimeout 없이 @Retryable만 사용

```kotlin
// ❌ 타임아웃 없으면 느린 호출에서 코루틴이 계속 대기
@Retryable(maxRetries = 2)
suspend fun callExternalApi(): Response {
    return webClient.get().uri("/api").retrieve().awaitBody()
}

// ✅ withTimeout으로 개별 호출 시간 제한
@Retryable(maxRetries = 2)
suspend fun callExternalApi(): Response =
    withTimeout(3.seconds) {
        webClient.get().uri("/api").retrieve().awaitBody()
    }
```

### 3. Redis 같은 빠른 연산에 재시도 적용

```kotlin
// ❌ Redis는 실패하면 재시도해도 같은 결과 (보통 인프라 문제)
@Retryable(maxRetries = 3)
suspend fun getFromRedis(key: String) { ... }

// ✅ Redis는 withTimeout만으로 충분
suspend fun getFromRedis(key: String) =
    withTimeout(timeouts.redisOperation) { ... }
```

---

## Spring Retry (레거시) vs Spring Framework 7 네이티브

| | Spring Retry (레거시) | Spring Framework 7 네이티브 |
|---|---|---|
| 패키지 | `org.springframework.retry.annotation` | `org.springframework.resilience.annotation` |
| 의존성 | 별도 라이브러리 필요 | Spring Core에 내장 |
| 상태 | 유지보수 모드 | 현행 개발 |
| Reactive 지원 | 제한적 | Mono/Flux 네이티브 지원 |

→ Spring Boot 4 프로젝트에서는 네이티브 `@Retryable`을 사용한다.

---

## 더 알아보기

- **Spring 공식 문서**: [Resilience Features](https://docs.spring.io/spring-framework/reference/core/resilience.html)
- **Spring 블로그**: [Core Spring Resilience Features](https://spring.io/blog/2025/09/09/core-spring-resilience-features/)
- **이 프로젝트 타임아웃 설정**: `common/infrastructure`의 `TimeoutProperties`
- **이 프로젝트 Kafka 에러 처리**: `common/infrastructure`의 `KafkaErrorConfig`
