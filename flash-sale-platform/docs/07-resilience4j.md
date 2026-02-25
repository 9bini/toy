# 7. Resilience4j (장애 대응)

> **한 줄 요약**: 외부 서비스 장애가 우리 시스템 전체로 전파되는 것을 막아주는 경량 장애 대응 라이브러리

---

## 왜 필요한가?

### 장애 전파의 공포

```
상황: 결제 API 서버가 느려짐 (응답 5초 → 30초)

주문 서비스 → 결제 API 호출 → 30초 대기...
           → 결제 API 호출 → 30초 대기...
           → 결제 API 호출 → 30초 대기...
           (스레드/코루틴이 모두 대기 상태)
           → 주문 서비스도 응답 불가!
           → Gateway도 응답 불가!
           → 전체 시스템 다운!
```

**하나의 느린 외부 서비스** → **연쇄적으로 전체 시스템 마비** (Cascading Failure)

---

## Resilience4j의 3가지 핵심 패턴

### 1. Circuit Breaker (서킷 브레이커)

전기 차단기처럼 **장애가 감지되면 호출을 차단**합니다.

```
상태 전이:

  [CLOSED] ──실패율 50% 초과──▶ [OPEN] ──60초 경과──▶ [HALF_OPEN]
     ▲                            │                       │
     │                        즉시 실패 반환            일부 요청만 통과
     │                        (빠른 실패)              성공 → CLOSED
     └──────────────────────────────────────────────── 실패 → OPEN
```

| 상태 | 동작 | 설명 |
|------|------|------|
| **CLOSED** (정상) | 모든 요청 통과 | 실패율 모니터링 중 |
| **OPEN** (차단) | 모든 요청 즉시 거부 | "결제 API 장애 중" → 호출하지 않고 바로 fallback |
| **HALF_OPEN** (시험) | 일부 요청만 통과 | 복구됐는지 확인 중 |

```kotlin
// Spring 설정 (application.yml)
resilience4j:
  circuitbreaker:
    instances:
      paymentApi:
        slidingWindowSize: 10          # 최근 10건 기준
        failureRateThreshold: 50       # 50% 이상 실패 시 OPEN
        waitDurationInOpenState: 60s   # OPEN 상태 60초 유지
        permittedNumberOfCallsInHalfOpenState: 3  # HALF_OPEN에서 3건만 시도
```

```kotlin
// 코드에서 사용
@Component
class PaymentApiAdapter(
    private val webClient: WebClient,
) : PaymentGatewayPort {

    @CircuitBreaker(name = "paymentApi", fallbackMethod = "paymentFallback")
    override suspend fun requestPayment(request: PaymentRequest): PaymentResponse {
        return webClient.post()
            .uri("/api/payments")
            .bodyValue(request)
            .retrieve()
            .awaitBody()
    }

    // 서킷이 OPEN일 때 호출되는 fallback
    suspend fun paymentFallback(request: PaymentRequest, ex: Exception): PaymentResponse {
        logger.warn { "결제 API 장애 중, fallback 실행: ${ex.message}" }
        return PaymentResponse(status = "PENDING", message = "결제 처리 지연 중")
    }
}
```

**실제 시나리오**:
1. 결제 API가 정상 → CLOSED 상태 → 모든 요청 통과
2. 연속 실패 발생 → 실패율 50% 초과 → OPEN 상태
3. OPEN 상태 → 결제 API 호출하지 않고 즉시 fallback 반환 (빠른 실패)
4. 60초 후 → HALF_OPEN → 3건만 시도
5. 성공하면 → CLOSED 복귀 / 실패하면 → 다시 OPEN

### 2. Retry (재시도)

일시적인 오류(네트워크 순간 끊김 등)를 자동으로 재시도합니다.

```kotlin
resilience4j:
  retry:
    instances:
      kafkaProducer:
        maxAttempts: 3                    # 최대 3회 시도
        waitDuration: 500ms               # 시도 사이 500ms 대기
        retryExceptions:                  # 이 예외만 재시도
          - java.net.ConnectException
          - org.apache.kafka.common.errors.TimeoutException
```

```kotlin
@Retry(name = "kafkaProducer")
suspend fun publishEvent(topic: String, key: String, event: String) {
    kafkaTemplate.send(topic, key, event).asDeferred().await()
}
```

```
1차 시도 → 네트워크 오류 → 500ms 대기
2차 시도 → 네트워크 오류 → 500ms 대기
3차 시도 → 성공!
```

### 3. Rate Limiter (처리율 제한)

일정 시간 동안 허용되는 호출 횟수를 제한합니다.

```kotlin
resilience4j:
  ratelimiter:
    instances:
      externalApi:
        limitForPeriod: 100           # 1초에 100건
        limitRefreshPeriod: 1s
        timeoutDuration: 0            # 초과 시 즉시 거부
```

---

## 이 프로젝트에서의 활용

### 어디에 적용하는가?

```
[주문 서비스]
    ├── Redis 호출 ← withTimeout (코루틴 타임아웃)
    ├── Kafka 발행 ← @Retry (재시도)
    ├── 결제 API 호출 ← @CircuitBreaker + @Retry
    └── DB 쿼리 ← withTimeout

[Gateway]
    └── Rate Limiting ← Redis Token Bucket + Nginx Rate Limit (2중 방어)
```

### Resilience4j + Coroutines 조합

```kotlin
class OrderUseCaseImpl(
    private val stockPort: StockPort,
    private val paymentGateway: PaymentGatewayPort,
    private val timeouts: TimeoutProperties,
) {
    suspend fun placeOrder(command: PlaceOrderCommand): Result<Order, OrderError> {
        // 1. Redis 재고 조회 - 코루틴 타임아웃
        val stock = withTimeout(timeouts.redisOperation) {
            stockPort.getRemaining(command.productId)
        }

        // 2. 결제 요청 - 서킷 브레이커 + 타임아웃
        val paymentResult = withTimeout(timeouts.paymentApi) {
            paymentGateway.requestPayment(PaymentRequest(...))
            // paymentGateway에는 @CircuitBreaker가 걸려 있음
        }

        return Result.success(order)
    }
}
```

### 의존성 설정

```kotlin
// common/infrastructure/build.gradle.kts
dependencies {
    api(libs.bundles.resilience4j)  // resilience4j-spring-boot3 + reactor + kotlin
    api("org.springframework.boot:spring-boot-starter-aop")  // @어노테이션 지원
}
```

---

## 서킷 브레이커 상태 모니터링

Actuator와 Prometheus를 통해 서킷 브레이커 상태를 모니터링할 수 있습니다.

```
# Prometheus 메트릭
resilience4j_circuitbreaker_state{name="paymentApi"} = 0  # 0=CLOSED, 1=OPEN, 2=HALF_OPEN
resilience4j_circuitbreaker_failure_rate{name="paymentApi"} = 12.5
resilience4j_circuitbreaker_calls_total{name="paymentApi", kind="successful"} = 1234
resilience4j_circuitbreaker_calls_total{name="paymentApi", kind="failed"} = 56
```

Grafana 대시보드에서 이 메트릭을 시각화하여 실시간으로 장애 상태를 파악합니다.

---

## Resilience4j vs Spring Cloud Circuit Breaker

| | Resilience4j | Hystrix (Netflix) |
|---|---|---|
| 상태 | 활발한 개발 중 | 유지보수 모드 (2018년 중단) |
| 경량성 | 가벼움 (필요한 것만 선택) | 무거움 |
| Kotlin 지원 | `resilience4j-kotlin` 모듈 | 없음 |
| 코루틴 지원 | 지원 | 미지원 |

→ 현재 Spring 생태계에서 장애 대응의 사실상 표준

---

## 더 알아보기

- **Resilience4j 공식**: [resilience4j.readme.io](https://resilience4j.readme.io/)
- **Spring Boot 통합**: `resilience4j-spring-boot3`
- **이 프로젝트 의존성**: `common/infrastructure/build.gradle.kts`의 `resilience4j` 번들
