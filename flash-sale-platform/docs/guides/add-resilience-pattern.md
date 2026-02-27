# Resilience 패턴 적용 가이드

> payment-service의 **"결제 API 호출"**을 예제로 Step-by-Step 따라하기

---

## 목차

1. [언제 필요한가?](#1-언제-필요한가)
2. [Step 1: withTimeout 적용](#step-1-withtimeout-적용)
3. [Step 2: @Retryable 적용](#step-2-retryable-적용)
4. [Step 3: @ConcurrencyLimit 적용 (선택)](#step-3-concurrencylimit-적용-선택)
5. [적용 판단 기준](#적용-판단-기준)
6. [자주 하는 실수](#자주-하는-실수)

---

## 1. 언제 필요한가?

### withTimeout — 항상 필요

모든 외부 호출에는 타임아웃이 필수다. 느린 호출이 코루틴을 점유하면 전체 시스템이 마비된다.

### @Retryable — 일시적 오류가 발생할 수 있는 곳

- 외부 HTTP API 호출 (네트워크 순단, 일시적 5xx)
- Kafka 메시지 발행 (브로커 일시 장애)
- 서비스 간 HTTP 호출

### @ConcurrencyLimit — 외부 리소스 보호가 필요한 곳

- 외부 API에 동시 호출 수 제한이 있을 때
- DB 커넥션 풀 보호가 필요할 때

---

## Step 1: withTimeout 적용

```kotlin
package com.flashsale.payment.adapter.out.external

import com.flashsale.common.config.TimeoutProperties
import com.flashsale.common.logging.Log
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Component
class ExternalPaymentAdapter(
    private val webClient: WebClient,
    private val timeouts: TimeoutProperties,
) : PaymentGatewayPort {
    companion object : Log

    override suspend fun requestPayment(
        orderId: String,
        amount: Long,
    ): PaymentResult =
        withTimeout(timeouts.paymentApi) {  // 3초
            webClient.post()
                .uri("/api/payments")
                .bodyValue(mapOf("orderId" to orderId, "amount" to amount))
                .retrieve()
                .awaitBody()
        }
}
```

**포인트**:
- `TimeoutProperties`를 주입받아 사용 (하드코딩 금지)
- 타임아웃 초과 시 `TimeoutCancellationException` 발생

---

## Step 2: @Retryable 적용

```kotlin
import kotlinx.coroutines.TimeoutCancellationException
import org.springframework.resilience.annotation.Retryable
import java.io.IOException

@Component
class ExternalPaymentAdapter(
    private val webClient: WebClient,
    private val timeouts: TimeoutProperties,
) : PaymentGatewayPort {
    companion object : Log

    @Retryable(
        maxRetries = 2,                    // 최대 2회 재시도 (총 3회 시도)
        delay = 1000,                      // 1초 대기
        multiplier = 2.0,                  // 지수 백오프: 1s → 2s
        maxDelay = 5000,                   // 최대 5초
        jitter = 200,                      // ±200ms 지터
        includes = [
            IOException::class,
            TimeoutCancellationException::class,
        ],
    )
    override suspend fun requestPayment(
        orderId: String,
        amount: Long,
    ): PaymentResult =
        withTimeout(timeouts.paymentApi) {
            webClient.post()
                .uri("/api/payments")
                .bodyValue(mapOf("orderId" to orderId, "amount" to amount))
                .retrieve()
                .awaitBody()
        }
}
```

**실행 흐름**:
```
1차 시도 → 타임아웃 (3초) → TimeoutCancellationException
→ 1000±200ms 대기
2차 시도 → IOException
→ 2000±200ms 대기 (지수 백오프)
3차 시도 → 성공!
```

**포인트**:
- `@EnableResilientMethods`는 `FlashSaleCommonAutoConfiguration`에 이미 선언됨
- `includes`로 재시도할 예외를 명시 — 비즈니스 에러(잔액 부족 등)는 포함하지 않음
- `jitter`로 thundering herd 방지

---

## Step 3: @ConcurrencyLimit 적용 (선택)

외부 API가 동시 호출 수를 제한하는 경우:

```kotlin
import org.springframework.resilience.annotation.ConcurrencyLimit

@Component
class ExternalNotificationAdapter : NotificationPort {

    @ConcurrencyLimit(10)  // 최대 10건 동시 발송
    @Retryable(maxRetries = 3, delay = 2000)
    override suspend fun sendPush(userId: String, message: String) {
        // 외부 Push API 호출
    }
}
```

---

## 적용 판단 기준

| 외부 호출 대상 | withTimeout | @Retryable | @ConcurrencyLimit |
|----------------|:-----------:|:----------:|:-----------------:|
| Redis GET/SET | O (100ms) | X | X |
| Redis Lua Script | O (200ms) | X | X |
| Kafka 메시지 발행 | O (1s) | O (3회) | X |
| 외부 결제 API | O (3s) | O (2회, 지수 백오프) | 상황에 따라 |
| 외부 알림 API | O (2s) | O (3회) | O (동시 제한) |
| DB 쿼리 | O (2s) | X | X |
| 서비스 간 HTTP | O (2s) | O (2회) | X |

**원칙**:
- `withTimeout`: **모든** 외부 호출에 적용
- `@Retryable`: 일시적 오류가 발생할 수 있는 **네트워크 호출**에 적용
- `@ConcurrencyLimit`: 외부 리소스 보호가 **필요한 곳**에만 적용
- Redis/DB 같은 인프라는 실패 시 재시도해도 같은 결과 → withTimeout만으로 충분

---

## 자주 하는 실수

### 1. 비즈니스 에러에 Retry 적용

```kotlin
// ❌ 모든 예외 재시도 → "잔액 부족" 같은 비즈니스 에러도 재시도됨
@Retryable(maxRetries = 3)
suspend fun pay(amount: Long) { ... }

// ✅ 일시적 오류만 재시도
@Retryable(
    maxRetries = 3,
    includes = [IOException::class, TimeoutCancellationException::class],
)
suspend fun pay(amount: Long) { ... }
```

### 2. withTimeout 없이 @Retryable만 사용

```kotlin
// ❌ 느린 호출이 재시도마다 30초씩 대기 → 총 90초
@Retryable(maxRetries = 2)
suspend fun callApi(): Response { ... }

// ✅ 개별 호출에 타임아웃 설정
@Retryable(maxRetries = 2)
suspend fun callApi(): Response =
    withTimeout(3.seconds) { ... }
```

### 3. Redis에 @Retryable 적용

```kotlin
// ❌ Redis 실패는 보통 인프라 문제 → 재시도 무의미
@Retryable(maxRetries = 3)
suspend fun getFromRedis(key: String) { ... }

// ✅ withTimeout만 적용
suspend fun getFromRedis(key: String) =
    withTimeout(timeouts.redisOperation) { ... }
```

### 4. @EnableResilientMethods 누락

`@Retryable`이 동작하려면 `@EnableResilientMethods`가 필요하다.
이 프로젝트에서는 `FlashSaleCommonAutoConfiguration`에 선언되어 있으므로
별도 설정 없이 모든 서비스에서 사용 가능하다.
