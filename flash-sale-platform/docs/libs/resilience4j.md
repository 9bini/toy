# Resilience4j

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [Circuit Breaker (서킷 브레이커)](#3-circuit-breaker-서킷-브레이커)
4. [Retry (재시도)](#4-retry-재시도)
5. [Rate Limiter (처리율 제한)](#5-rate-limiter-처리율-제한)
6. [이 프로젝트에서의 활용](#6-이-프로젝트에서의-활용)
7. [자주 하는 실수 / 주의사항](#7-자주-하는-실수--주의사항)
8. [정리 / 한눈에 보기](#8-정리--한눈에-보기)
9. [더 알아보기](#9-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

외부 서비스 장애가 우리 시스템으로 **퍼지는 것을 막는** 경량 장애 대응 라이브러리.

### 비유: 전기 차단기

집에서 과전류가 흐르면 **차단기(Breaker)**가 내려가서 화재를 방지한다.
Resilience4j도 마찬가지로, 외부 서비스가 고장 나면 호출을 차단해서 우리 서비스를 보호한다.

---

## 2. 왜 필요한가?

### 장애 전파 (Cascading Failure)

```
결제 서비스 장애 발생 (응답 시간 30초):

주문 서비스 → 결제 서비스 호출 (30초 대기) → 스레드 점유
           → 결제 서비스 호출 (30초 대기) → 스레드 점유
           → ... (스레드 전부 소진)
           → 주문 서비스도 다운!
           → Gateway도 다운!
           → 전체 시스템 다운! 💀
```

### Resilience4j로 해결

```
결제 서비스 장애 발생:

주문 서비스 → 결제 서비스 호출 → 실패
           → 결제 서비스 호출 → 실패
           → 실패율 50% 초과 → 서킷 OPEN!
           → 더 이상 호출 안 함 → 즉시 fallback 응답
           → 주문 서비스는 정상 가동 유지! ✅
```

---

## 3. Circuit Breaker (서킷 브레이커)

### 3가지 상태

```
                   실패율 ≥ 50%
    ┌─────────┐ ──────────────► ┌──────────┐
    │ CLOSED  │                 │   OPEN   │
    │ (정상)   │ ◄────────────── │  (차단)   │
    └─────────┘   성공 시 복귀    └──────┬───┘
         ↑                             │
         │        60초 대기 후          │
         │    ┌───────────────┐        │
         └────│  HALF_OPEN    │ ◄──────┘
              │  (시험 호출)    │
              └───────────────┘
```

| 상태 | 동작 | 설명 |
|------|------|------|
| **CLOSED** (정상) | 모든 호출 허용 | 실패율을 모니터링 |
| **OPEN** (차단) | 모든 호출 차단 | 즉시 fallback 반환 (빠른 실패) |
| **HALF_OPEN** (시험) | 일부 호출만 허용 | 복구되었는지 테스트 |

### 설정 상세

```yaml
resilience4j:
  circuitbreaker:
    instances:
      paymentApi:
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 10           # 최근 10건 기준
        failureRateThreshold: 50        # 실패율 50% 이상 → OPEN
        waitDurationInOpenState: 60s    # OPEN 후 60초 대기 → HALF_OPEN
        permittedNumberOfCallsInHalfOpenState: 5  # HALF_OPEN에서 5건 시험
        minimumNumberOfCalls: 5         # 최소 5건은 호출해야 판단
```

| 설정 | 의미 |
|------|------|
| `slidingWindowSize: 10` | 최근 10건의 호출 결과를 보고 판단 |
| `failureRateThreshold: 50` | 10건 중 5건 이상 실패 → OPEN |
| `waitDurationInOpenState: 60s` | 60초 동안 차단 후 HALF_OPEN |
| `permittedNumberOfCallsInHalfOpenState: 5` | 시험 호출 5건 |
| `minimumNumberOfCalls: 5` | 최소 5건은 쌓여야 판단 시작 |

### 코드 사용법

```kotlin
@CircuitBreaker(name = "paymentApi", fallbackMethod = "paymentFallback")
suspend fun requestPayment(request: PaymentRequest): PaymentResponse {
    return webClient.post()
        .uri("/api/payments")
        .bodyValue(request)
        .retrieve()
        .bodyToMono<PaymentResponse>()
        .awaitSingle()
}

// fallback: 원본과 같은 파라미터 + Exception
suspend fun paymentFallback(
    request: PaymentRequest,
    ex: Exception
): PaymentResponse {
    logger.warn { "결제 서비스 장애, fallback: ${ex.message}" }
    return PaymentResponse(status = "PENDING", message = "결제 서비스 복구 중")
}
```

---

## 4. Retry (재시도)

일시적 오류(네트워크 순단, 타임아웃)를 자동으로 재시도한다.

### 설정

```yaml
resilience4j:
  retry:
    instances:
      kafkaProducer:
        maxAttempts: 3              # 최대 3회 시도
        waitDuration: 500ms         # 시도 간 500ms 대기
        retryExceptions:            # 이 예외만 재시도
          - java.io.IOException
          - java.util.concurrent.TimeoutException
```

### 재시도 전략

```
고정 간격 (Fixed):      500ms → 500ms → 500ms
지수 백오프 (Exponential): 500ms → 1000ms → 2000ms
랜덤 (Random):          300ms → 800ms → 100ms
```

### 코드 사용법

```kotlin
@Retry(name = "kafkaProducer")
suspend fun publishEvent(topic: String, event: String) {
    kafkaTemplate.send(topic, event).asDeferred().await()
}
```

---

## 5. Rate Limiter (처리율 제한)

초당 허용 호출 수를 제한한다.

```yaml
resilience4j:
  ratelimiter:
    instances:
      orderApi:
        limitForPeriod: 100       # 1초에 100건
        limitRefreshPeriod: 1s    # 1초마다 리셋
        timeoutDuration: 500ms    # 대기 최대 500ms
```

Nginx Rate Limiting과의 차이:
- **Nginx**: IP 기반, 인프라 수준 (거친 필터)
- **Resilience4j**: API/사용자 기반, 애플리케이션 수준 (정밀 필터)

---

## 6. 이 프로젝트에서의 활용

### 의존성

```kotlin
// common/infrastructure/build.gradle.kts
api(libs.bundles.resilience4j)  // spring-boot3 + reactor + kotlin
api("org.springframework.boot:spring-boot-starter-aop")
```

### 3개 모듈 역할

| 모듈 | 역할 |
|------|------|
| `resilience4j-spring-boot3` | @어노테이션 + 자동 설정 |
| `resilience4j-reactor` | Mono/Flux 통합 |
| `resilience4j-kotlin` | 코루틴 통합 |

### Prometheus 메트릭

```
resilience4j_circuitbreaker_state{name="paymentApi"} 0
# 0 = CLOSED, 1 = OPEN, 2 = HALF_OPEN

resilience4j_circuitbreaker_failure_rate{name="paymentApi"} 10.0
# 실패율 10%
```

---

## 7. 자주 하는 실수 / 주의사항

### fallback 메서드 시그니처

```kotlin
// ❌ fallback 파라미터가 원본과 다름 → 실행 안 됨
@CircuitBreaker(name = "api", fallbackMethod = "fallback")
suspend fun call(request: Request): Response { ... }
suspend fun fallback(ex: Exception): Response { ... }  // request 파라미터 누락!

// ✅ 원본 파라미터 + Exception 마지막에 추가
suspend fun fallback(request: Request, ex: Exception): Response { ... }
```

### 모든 예외에 Retry

```kotlin
// ❌ 비즈니스 에러(잔액 부족 등)까지 재시도 → 의미 없는 재시도
retryExceptions: [java.lang.Exception]

// ✅ 일시적 오류만 재시도
retryExceptions:
  - java.io.IOException
  - java.util.concurrent.TimeoutException
```

---

## 8. 정리 / 한눈에 보기

| 패턴 | 역할 | 비유 |
|------|------|------|
| Circuit Breaker | 반복 실패 시 호출 차단 | 전기 차단기 |
| Retry | 일시적 오류 자동 재시도 | 전화 다시 걸기 |
| Rate Limiter | 초당 호출 수 제한 | 입장 인원 제한 |

---

## 9. 더 알아보기

- [Resilience4j 공식 문서](https://resilience4j.readme.io/docs)
- [Resilience4j GitHub](https://github.com/resilience4j/resilience4j)
