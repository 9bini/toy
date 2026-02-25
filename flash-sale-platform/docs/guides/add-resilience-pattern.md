# 서킷 브레이커 + 재시도 추가 가이드

> payment-service의 **"결제 API 호출"**을 예제로 Step-by-Step 따라하기

---

## 목차

1. [언제 필요한가?](#1-언제-필요한가)
2. [Step 1: application.yml 설정](#step-1-applicationyml-설정)
3. [Step 2: @CircuitBreaker 적용](#step-2-circuitbreaker-적용)
4. [Step 3: @Retry 적용](#step-3-retry-적용)
5. [Step 4: Prometheus 메트릭 확인](#step-4-prometheus-메트릭-확인)
6. [이 프로젝트의 기존 설정 참조](#이-프로젝트의-기존-설정-참조)
7. [자주 하는 실수](#자주-하는-실수)

---

## 1. 언제 필요한가?

### 서킷 브레이커 (Circuit Breaker)

**외부 서비스가 장애일 때**, 계속 호출하면 우리 서비스도 죽는다.
서킷 브레이커가 반복 실패를 감지하면 **호출을 차단**하여 우리 서비스를 보호한다.

```
서킷 브레이커 없이:
  결제 API 장애 (30초 응답) → 주문 서비스 스레드 점유 → 전체 시스템 다운

서킷 브레이커 있으면:
  결제 API 장애 → 실패율 50% 초과 → 서킷 OPEN → 즉시 fallback → 주문 서비스 정상
```

### 재시도 (Retry)

**일시적 오류**(네트워크 순단, 타임아웃)는 다시 시도하면 성공할 수 있다.

```
재시도 없이:
  네트워크 순단 → 즉시 실패 → 사용자에게 에러

재시도 있으면:
  네트워크 순단 → 500ms 대기 → 재시도 → 성공!
```

---

## Step 1: application.yml 설정

### 서킷 브레이커 설정

```yaml
# services/payment-service/src/main/resources/application.yml

resilience4j:
  circuitbreaker:
    instances:
      payment-api:                                    # ← 인스턴스 이름 (코드에서 참조)
        sliding-window-type: TIME_BASED               # 시간 기반 (또는 COUNT_BASED)
        sliding-window-size: 30                       # 최근 30초 내 호출 결과 분석
        failure-rate-threshold: 50                    # 실패율 50% 이상 → OPEN
        wait-duration-in-open-state: 15s              # OPEN 후 15초 대기 → HALF_OPEN
        permitted-number-of-calls-in-half-open-state: 5  # HALF_OPEN에서 5건 시험
        minimum-number-of-calls: 10                   # 최소 10건은 쌓여야 판단
        slow-call-duration-threshold: 3s              # 3초 이상 → 느린 호출
        slow-call-rate-threshold: 80                  # 느린 호출 80% 이상 → OPEN
```

### COUNT_BASED vs TIME_BASED 선택 기준

| | COUNT_BASED | TIME_BASED |
|---|---|---|
| 기준 | 최근 N건 | 최근 N초 |
| 적합 | 호출량 일정 | 호출량 변동 큼 |
| 예시 | DB 호출 (꾸준) | 외부 API (간헐적) |

### 재시도 설정

```yaml
resilience4j:
  retry:
    instances:
      payment-api:
        max-attempts: 2                # 최대 2회 시도 (원래 1회 + 재시도 1회)
        wait-duration: 1s              # 재시도 간격 1초
        retry-exceptions:              # 이 예외만 재시도
          - java.io.IOException
          - java.util.concurrent.TimeoutException
        ignore-exceptions:             # 이 예외는 재시도 안 함
          - com.flashsale.payment.domain.error.InsufficientBalanceException
      kafka-produce:
        max-attempts: 3
        wait-duration: 500ms
```

---

## Step 2: @CircuitBreaker 적용

### 어댑터에 어노테이션 추가

```kotlin
package com.flashsale.payment.adapter.out.external

import com.flashsale.common.logging.Log
import com.flashsale.payment.application.port.out.PaymentGatewayPort
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Component
class ExternalPaymentAdapter(
    private val webClient: WebClient,
) : PaymentGatewayPort {
    companion object : Log

    @CircuitBreaker(
        name = "payment-api",                    // application.yml의 인스턴스 이름
        fallbackMethod = "requestPaymentFallback",  // 서킷 OPEN 시 호출할 메서드
    )
    override suspend fun requestPayment(
        orderId: String,
        amount: Long,
    ): PaymentResult {
        return webClient.post()
            .uri("/api/payments")
            .bodyValue(mapOf("orderId" to orderId, "amount" to amount))
            .retrieve()
            .awaitBody<PaymentResult>()
    }

    /**
     * fallback 메서드: 서킷 OPEN 또는 호출 실패 시 실행.
     *
     * 규칙:
     * 1. 원본 메서드와 동일한 파라미터
     * 2. 마지막에 Exception 파라미터 추가
     * 3. 반환 타입 동일
     */
    suspend fun requestPaymentFallback(
        orderId: String,
        amount: Long,
        ex: Exception,
    ): PaymentResult {
        logger.warn { "결제 API 장애, fallback: orderId=$orderId, error=${ex.message}" }
        return PaymentResult(
            status = "PENDING",
            message = "결제 서비스 복구 중. 잠시 후 재시도됩니다.",
        )
    }
}
```

### 서킷 상태 전이

```
                   실패율 ≥ 50%
    ┌─────────┐ ──────────────► ┌──────────┐
    │ CLOSED  │                 │   OPEN   │
    │ (정상)   │ ◄────────────── │  (차단)   │
    └─────────┘   성공 시 복귀    └──────┬───┘
         ↑                             │
         │        15초 대기 후          │
         │    ┌───────────────┐        │
         └────│  HALF_OPEN    │ ◄──────┘
              │  (시험 호출)    │
              └───────────────┘
```

---

## Step 3: @Retry 적용

```kotlin
import io.github.resilience4j.retry.annotation.Retry

@Component
class KafkaOrderEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    companion object : Log

    @Retry(name = "kafka-produce")   // 최대 3회 시도, 500ms 간격
    suspend fun publish(topic: String, key: String, event: Any) {
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(topic, key, json)
            .asDeferred()
            .await()
    }
}
```

### 서킷 브레이커 + 재시도 함께 사용

```kotlin
// 재시도가 먼저 실행되고, 모두 실패하면 서킷 브레이커에 실패로 기록됨
// 실행 순서: Retry → CircuitBreaker → 실제 호출

@CircuitBreaker(name = "payment-api", fallbackMethod = "fallback")
@Retry(name = "payment-api")
suspend fun requestPayment(orderId: String, amount: Long): PaymentResult {
    return webClient.post().uri("/api/payments")...
}
// 호출 흐름:
// 1. Retry가 감싸고
// 2. 그 안에서 CircuitBreaker가 감싸고
// 3. 가장 안쪽에서 실제 호출
```

---

## Step 4: Prometheus 메트릭 확인

### 서킷 브레이커 메트릭

```
# 서킷 상태 (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
resilience4j_circuitbreaker_state{name="payment-api"} 0

# 실패율 (%)
resilience4j_circuitbreaker_failure_rate{name="payment-api"} 10.0

# 총 호출 수
resilience4j_circuitbreaker_calls_seconds_count{name="payment-api",kind="successful"} 95
resilience4j_circuitbreaker_calls_seconds_count{name="payment-api",kind="failed"} 5
```

### 재시도 메트릭

```
# 재시도 없이 성공
resilience4j_retry_calls_total{name="kafka-produce",kind="successful_without_retry"} 100

# 재시도 후 성공
resilience4j_retry_calls_total{name="kafka-produce",kind="successful_with_retry"} 5

# 모든 재시도 실패
resilience4j_retry_calls_total{name="kafka-produce",kind="failed_with_retry"} 1
```

### Prometheus에서 확인

```
http://localhost:9090
쿼리: resilience4j_circuitbreaker_state{name="payment-api"}
```

---

## 이 프로젝트의 기존 설정 참조

### gateway (application.yml)

```yaml
resilience4j:
  ratelimiter:
    instances:
      gateway-default:
        limit-for-period: 1000    # 1초에 1000건
        limit-refresh-period: 1s
        timeout-duration: 0s      # 대기 없이 즉시 거부
```

### queue-service

```yaml
resilience4j:
  circuitbreaker:
    instances:
      redis-queue:                       # Redis 장애 시
        sliding-window-type: COUNT_BASED
        sliding-window-size: 100
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
```

### order-service

```yaml
resilience4j:
  circuitbreaker:
    instances:
      stock-decrement:                    # 재고 차감 (Redis)
        sliding-window-size: 100
        failure-rate-threshold: 50
      order-db:                           # DB 접근
        sliding-window-size: 50
        failure-rate-threshold: 60
  retry:
    instances:
      kafka-produce:                      # Kafka 발행
        max-attempts: 3
        wait-duration: 500ms
```

### payment-service

```yaml
resilience4j:
  circuitbreaker:
    instances:
      payment-api:                        # 외부 결제 API
        sliding-window-type: TIME_BASED
        sliding-window-size: 30
        slow-call-duration-threshold: 3s  # 느린 호출 감지
        slow-call-rate-threshold: 80
      payment-db:                         # DB 접근
        sliding-window-size: 50
  retry:
    instances:
      payment-api:                        # 결제 API 재시도
        max-attempts: 2
        wait-duration: 1s
      kafka-produce:
        max-attempts: 3
        wait-duration: 500ms
```

### notification-service

```yaml
resilience4j:
  circuitbreaker:
    instances:
      notification-api:                   # 외부 알림 API
        sliding-window-size: 50
        wait-duration-in-open-state: 30s  # 외부 API는 복구 시간 길게
  retry:
    instances:
      notification-send:                  # 알림 발송 재시도
        max-attempts: 3
        wait-duration: 2s
```

---

## 자주 하는 실수

### 1. fallback 시그니처 불일치

```kotlin
// ❌ 원본 파라미터 누락 → fallback 실행 안 됨
@CircuitBreaker(name = "api", fallbackMethod = "fallback")
suspend fun call(orderId: String, amount: Long): Result { ... }

suspend fun fallback(ex: Exception): Result { ... }  // orderId, amount 빠짐!

// ✅ 원본 파라미터 + Exception
suspend fun fallback(orderId: String, amount: Long, ex: Exception): Result { ... }
```

### 2. 비즈니스 에러에 Retry 적용

```yaml
# ❌ 모든 예외 재시도 → "잔액 부족" 같은 비즈니스 에러도 재시도됨
retry-exceptions: [java.lang.Exception]

# ✅ 일시적 오류만 재시도
retry-exceptions:
  - java.io.IOException
  - java.util.concurrent.TimeoutException
ignore-exceptions:
  - com.flashsale.payment.domain.error.InsufficientBalanceException
```

### 3. minimumNumberOfCalls 너무 작게 설정

```yaml
# ❌ 최소 1건 → 첫 실패에 바로 OPEN
minimum-number-of-calls: 1

# ✅ 충분한 샘플 후 판단
minimum-number-of-calls: 10
```

### 4. AOP 의존성 누락

```kotlin
// ❌ @CircuitBreaker 어노테이션이 동작하지 않음
// → spring-boot-starter-aop 의존성 필요

// common/infrastructure/build.gradle.kts에 이미 포함:
// api("org.springframework.boot:spring-boot-starter-aop")
```
