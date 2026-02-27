# 프로젝트 구조와 규칙

---

## 목차

1. [전체 디렉토리 구조](#1-전체-디렉토리-구조)
2. [모듈 의존성](#2-모듈-의존성)
3. [서비스별 책임](#3-서비스별-책임)
4. [헥사고날 아키텍처 패키지 구조](#4-헥사고날-아키텍처-패키지-구조)
5. [네이밍 규칙](#5-네이밍-규칙)
6. [공통 모듈 상세](#6-공통-모듈-상세)
7. [application.yml 구조](#7-applicationyml-구조)
8. [구현 순서](#8-구현-순서)

---

## 1. 전체 디렉토리 구조

```
flash-sale-platform/
├── CLAUDE.md                    # 프로젝트 규칙 + 커밋 컨벤션
├── build.gradle.kts             # 루트 빌드 (공통 설정)
├── settings.gradle.kts          # 모듈 등록
├── gradle/
│   └── libs.versions.toml       # 의존성 버전 중앙 관리 (Version Catalog)
│
├── common/                      # ── 공유 모듈 ──
│   ├── domain/                  #   순수 도메인 (외부 의존성 없음)
│   │   └── src/main/kotlin/com/flashsale/common/domain/
│   │       ├── Result.kt        #     비즈니스 에러 처리
│   │       ├── DomainEvent.kt   #     Kafka 이벤트 인터페이스
│   │       └── IdGenerator.kt   #     ULID 기반 고유 ID 생성
│   │
│   └── infrastructure/          #   공유 인프라 설정
│       ├── src/main/kotlin/com/flashsale/common/
│       │   ├── config/
│       │   │   ├── FlashSaleCommonAutoConfiguration.kt  # 자동 빈 등록
│       │   │   └── Timeouts.kt                          # 타임아웃 중앙 설정
│       │   ├── redis/
│       │   │   └── RedisKeys.kt                         # Redis 키 패턴 관리
│       │   ├── kafka/
│       │   │   └── KafkaTopics.kt                       # Kafka 토픽명 상수
│       │   └── logging/
│       │       ├── Log.kt                               # 로거 유틸
│       │       ├── CoroutineMdc.kt                      # 코루틴 MDC 전파
│       │       └── RequestTracingFilter.kt              # 요청 추적 필터
│       └── src/testFixtures/kotlin/com/flashsale/common/test/
│           └── IntegrationTestBase.kt                   # 통합 테스트 베이스
│
├── services/                    # ── 마이크로서비스 ──
│   ├── gateway/                 #   API Gateway (포트 8080)
│   ├── queue-service/           #   대기열 관리 (포트 8081)
│   ├── order-service/           #   주문 처리 (포트 8082)
│   ├── payment-service/         #   결제 처리 (포트 8083)
│   └── notification-service/    #   알림 전송 (포트 8084)
│
├── infra/                       # ── 인프라 설정 ──
│   ├── nginx/nginx.conf         #   Nginx 리버스 프록시 설정
│   └── prometheus/prometheus.yml #  Prometheus 수집 설정
│
├── tests/                       # ── 통합/E2E 테스트 ──
│   └── integration/
│
├── docker-compose.yml           # 개발 환경 인프라
├── docker-compose.ha.yml        # HA(고가용성) 오버레이
└── docs/                        # 문서
```

---

## 2. 모듈 의존성

```
의존성 방향: 항상 domain을 향한다 (외부 → 내부)

common:domain (순수 Kotlin, 외부 의존성 없음)
  ↑
common:infrastructure (Spring, Redis, Kafka 등)
  ↑
services/* (각 마이크로서비스)
```

### settings.gradle.kts

```kotlin
// 공통 모듈
include("common:domain")
include("common:infrastructure")

// 마이크로서비스
include("services:gateway")
include("services:queue-service")
include("services:order-service")
include("services:payment-service")
include("services:notification-service")

// 통합 테스트
include("tests:integration")
```

### 각 서비스의 build.gradle.kts 의존성 패턴

```kotlin
dependencies {
    implementation(project(":common:domain"))
    implementation(project(":common:infrastructure"))
    // ... 서비스별 추가 의존성
}
```

### 루트 build.gradle.kts (모든 서브프로젝트에 공통 적용)

```kotlin
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)  // JDK 21 고정
    }

    dependencies {
        // 모든 서브프로젝트에 공통 포함
        implementation(rootProject.libs.bundles.coroutines)   // 코루틴
        implementation(rootProject.libs.kotlin.logging)       // 로깅
        testImplementation(rootProject.libs.bundles.kotest)   // 테스트
        testImplementation(rootProject.libs.mockk)            // 모킹
    }
}
```

---

## 3. 서비스별 책임

| 서비스 | 포트 | 핵심 기술 | 역할 |
|--------|------|----------|------|
| **gateway** | 8080 | Redis Token Bucket | API Gateway, Rate Limiting (앱 레벨) |
| **queue-service** | 8081 | Redis Sorted Set, SSE | 대기열 관리, 실시간 순번 알림 |
| **order-service** | 8082 | Redis Lua Script, Redisson, R2DBC | 재고 차감, 주문 생성, 분산 락 |
| **payment-service** | 8083 | Saga, R2DBC | 결제 처리, 보상 트랜잭션 |
| **notification-service** | 8084 | Kafka Consumer, SSE | 알림 발송 (이메일, 푸시, SSE) |

### 서비스 간 통신 흐름

```
클라이언트 → Nginx → gateway → queue-service (대기열 진입)
                              ↓ (순번 도착)
                         order-service (주문 생성 + 재고 차감)
                              ↓ (Kafka: order.placed)
                         payment-service (결제 처리)
                              ↓ (Kafka: payment.completed)
                         notification-service (알림 발송)
```

---

## 4. 헥사고날 아키텍처 패키지 구조

각 서비스는 다음 패키지 구조를 따릅니다.

```
com.flashsale.{service}/
│
├── domain/                       # === 도메인 레이어 ===
│   ├── model/                    # 엔티티, Value Object
│   │   ├── Order.kt              #   data class (비즈니스 모델)
│   │   └── OrderStatus.kt        #   enum class (상태)
│   └── error/                    # 에러 타입
│       └── OrderError.kt         #   sealed interface
│
├── application/                  # === 애플리케이션 레이어 ===
│   ├── port/
│   │   ├── in/                   # 인바운드 포트 (외부 → 내부)
│   │   │   └── PlaceOrderUseCase.kt  # UseCase 인터페이스
│   │   └── out/                  # 아웃바운드 포트 (내부 → 외부)
│   │       ├── StockPort.kt      #   Redis 재고 추상화
│   │       └── OrderPersistencePort.kt  # DB 추상화
│   └── service/                  # 유스케이스 구현
│       └── PlaceOrderService.kt  #   비즈니스 로직 (Port만 의존)
│
├── adapter/                      # === 어댑터 레이어 ===
│   ├── in/                       # 인바운드 어댑터 (외부 → 내부)
│   │   └── web/
│   │       ├── OrderController.kt    # REST API
│   │       ├── OrderRequest.kt       # 요청 DTO
│   │       └── OrderResponse.kt      # 응답 DTO
│   └── out/                      # 아웃바운드 어댑터 (내부 → 외부)
│       ├── persistence/
│       │   ├── OrderEntity.kt        # R2DBC 엔티티 (@Table)
│       │   ├── OrderRepository.kt    # ReactiveCrudRepository
│       │   └── R2dbcOrderAdapter.kt  # OrderPersistencePort 구현
│       ├── redis/
│       │   └── RedisStockAdapter.kt  # StockPort 구현
│       └── kafka/
│           └── KafkaOrderEventPublisher.kt  # 이벤트 발행
│
└── config/                       # === Spring 설정 ===
    └── OrderConfig.kt            # 빈 등록 (필요 시)
```

### 의존성 규칙 (핵심!)

```
domain     → 아무것도 의존하지 않음 (순수 Kotlin)
application → domain만 의존 (Port 인터페이스 정의)
adapter    → application, domain 의존 (Port 구현)

❌ domain이 adapter를 import
❌ application이 Spring/Redis/Kafka를 직접 import
✅ adapter에서만 기술 세부사항(Redis, Kafka, R2DBC) 사용
```

---

## 5. 네이밍 규칙

### 클래스명

| 종류 | 패턴 | 예시 |
|------|------|------|
| 도메인 모델 | `{이름}` | `Order`, `Payment`, `OrderStatus` |
| 에러 타입 | `{도메인}Error` | `OrderError`, `PaymentError` |
| 인바운드 포트 | `{동사}{대상}UseCase` | `PlaceOrderUseCase`, `CancelOrderUseCase` |
| 아웃바운드 포트 | `{대상}Port` | `StockPort`, `OrderPersistencePort` |
| 유스케이스 구현 | `{동사}{대상}Service` | `PlaceOrderService` |
| Redis 어댑터 | `Redis{기능}Adapter` | `RedisStockAdapter`, `RedisQueueAdapter` |
| DB 어댑터 | `R2dbc{대상}Adapter` | `R2dbcOrderAdapter` |
| Kafka 어댑터 | `Kafka{대상}EventPublisher` | `KafkaOrderEventPublisher` |
| 컨트롤러 | `{도메인}Controller` | `OrderController`, `QueueController` |
| 이벤트 | `{도메인}{동사}Event` | `OrderPlacedEvent`, `PaymentCompletedEvent` |
| 요청 DTO | `{동작}Request` | `PlaceOrderRequest`, `CancelOrderRequest` |
| 응답 DTO | `{대상}Response` | `OrderResponse`, `QueuePositionResponse` |

### 파일 위치

```
모델 → domain/model/
에러 → domain/error/
포트 → application/port/in/ 또는 application/port/out/
서비스 → application/service/
컨트롤러 → adapter/in/web/
어댑터 → adapter/out/{기술}/
```

### Kafka 토픽 네이밍

```
flashsale.{도메인}.{이벤트}

예시:
  flashsale.order.placed
  flashsale.payment.completed
  flashsale.stock.decremented
```

### Redis 키 네이밍

```
{도메인}:{엔티티}:{id}

예시:
  stock:remaining:product-123
  queue:waiting:sale-event-1
  order:idempotency:key-abc
```

---

## 6. 공통 모듈 상세

### 6.1 Result<T, E> — 비즈니스 에러 처리

> 파일: `common/domain/src/.../Result.kt`

Exception 대신 **타입 시스템**으로 성공/실패를 표현한다.

```kotlin
sealed interface Result<out T, out E> {
    data class Success<T>(val value: T) : Result<T, Nothing>
    data class Failure<E>(val error: E) : Result<Nothing, E>
}

// 사용 예시
suspend fun placeOrder(request: OrderRequest): Result<Order, OrderError> {
    val stock = stockPort.getStock(request.productId)
        ?: return Result.failure(OrderError.ProductNotFound(request.productId))

    if (stock < request.quantity) {
        return Result.failure(OrderError.InsufficientStock(stock, request.quantity))
    }
    return Result.success(Order.create(request))
}

// 확장 함수: map, flatMap, mapError, getOrElse, getOrNull, fold, onSuccess, onFailure
```

### 6.2 DomainEvent — Kafka 이벤트 인터페이스

> 파일: `common/domain/src/.../DomainEvent.kt`

모든 Kafka 이벤트가 구현하는 공통 인터페이스.

```kotlin
interface DomainEvent {
    val aggregateId: String   // Kafka 파티셔닝 키 (같은 주문 이벤트 → 같은 파티션)
    val eventType: String     // "order.placed", "payment.completed"
    val occurredAt: Instant   // 이벤트 발생 시각
    val eventId: String       // 멱등성 보장용 고유 ID
}

// 구현 예시
data class OrderPlacedEvent(
    override val aggregateId: String,
    override val eventType: String = "order.placed",
    override val occurredAt: Instant = Instant.now(),
    override val eventId: String = IdGenerator.generate(),
    val productId: String,
    val quantity: Int,
    val userId: String,
) : DomainEvent
```

### 6.3 IdGenerator — ULID 기반 고유 ID

> 파일: `common/domain/src/.../IdGenerator.kt`

- 시간순 정렬 가능 (앞 10자리 = 타임스탬프)
- ThreadLocal SecureRandom으로 lock contention 없음
- 밀리초 내 충돌 없음 (뒤 16자리 = 랜덤)

```kotlin
val id = IdGenerator.generate()
// → "01HXY3ABCD..." (26자리 ULID)
```

### 6.4 RedisKeys — Redis 키 패턴 중앙 관리

> 파일: `common/infrastructure/src/.../redis/RedisKeys.kt`

```kotlin
object RedisKeys {
    object Stock {
        fun remaining(productId: String) = "stock:remaining:$productId"
        fun lock(productId: String) = "stock:lock:$productId"
    }
    object Queue {
        fun waiting(saleEventId: String) = "queue:waiting:$saleEventId"
        fun token(saleEventId: String, userId: String) = "queue:token:$saleEventId:$userId"
        fun status(saleEventId: String) = "queue:status:$saleEventId"
    }
    object RateLimit {
        fun bucket(clientId: String) = "ratelimit:bucket:$clientId"
    }
    object Order {
        fun idempotencyKey(key: String) = "order:idempotency:$key"
        fun userOrder(userId: String, saleEventId: String) = "order:user:$userId:$saleEventId"
    }
    object Session {
        fun user(sessionId: String) = "session:user:$sessionId"
    }
}
```

### 6.5 KafkaTopics — Kafka 토픽명 상수

> 파일: `common/infrastructure/src/.../kafka/KafkaTopics.kt`

```kotlin
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
    object Notification {
        const val SEND_REQUESTED = "flashsale.notification.send-requested"
    }
    fun dlq(originalTopic: String) = "$originalTopic.dlq"
}
```

### 6.6 TimeoutProperties — 타임아웃 중앙 설정

> 파일: `common/infrastructure/src/.../config/Timeouts.kt`

application.yml로 오버라이드 가능한 타임아웃 설정.

```kotlin
@ConfigurationProperties(prefix = "flashsale.timeout")
data class TimeoutProperties(
    val redisOperationMs: Long = 100,        // Redis 단순 연산
    val redisLuaScriptMs: Long = 200,        // Lua Script
    val distributedLockWaitMs: Long = 3000,  // 분산 락 대기
    val distributedLockLeaseMs: Long = 5000, // 분산 락 유지
    val kafkaProduceMs: Long = 1000,         // Kafka 발행
    val paymentApiMs: Long = 3000,           // 결제 API
    val dbQueryMs: Long = 2000,              // DB 쿼리
    val dbTransactionMs: Long = 5000,        // DB 트랜잭션
    val interServiceCallMs: Long = 2000,     // 서비스 간 호출
    val sseConnectionMs: Long = 300000,      // SSE 연결 유지
) {
    // Duration 변환 — withTimeout에서 직접 사용
    val redisOperation: Duration get() = redisOperationMs.milliseconds
    val paymentApi: Duration get() = paymentApiMs.milliseconds
    // ...
}

// 사용 예시
suspend fun getStock(productId: String): Int =
    withTimeout(timeouts.redisOperation) {
        redisTemplate.opsForValue().get(key).awaitSingleOrNull()?.toInt() ?: 0
    }
```

DI 불가능한 곳에서는 `DefaultTimeouts` 상수 사용:
```kotlin
object DefaultTimeouts {
    val REDIS_OPERATION = 100.milliseconds
    val PAYMENT_API = 3.seconds
    // ...
}
```

### 6.7 Log — 로거 유틸

> 파일: `common/infrastructure/src/.../logging/Log.kt`

```kotlin
// 사용법: companion object에 Log 인터페이스 상속
class OrderService {
    companion object : Log

    suspend fun placeOrder(request: OrderRequest) {
        logger.info { "주문 생성 시작: productId=${request.productId}" }
    }
}
```

### 6.8 withMdc — 코루틴 MDC 전파

> 파일: `common/infrastructure/src/.../logging/CoroutineMdc.kt`

코루틴 환경에서 MDC를 안전하게 전파.

```kotlin
withMdc(MdcKeys.ORDER_ID, orderId) {
    // 이 블록 안의 모든 로그에 orderId가 자동 포함
    logger.info { "주문 처리 중" }
}
```

표준 MDC 키: `MdcKeys.REQUEST_ID`, `SERVICE`, `USER_ID`, `ORDER_ID`, `PRODUCT_ID`

### 6.9 IntegrationTestBase — 통합 테스트 베이스

> 파일: `common/infrastructure/src/testFixtures/.../IntegrationTestBase.kt`

Testcontainers를 싱글턴으로 관리하는 베이스 클래스.

```kotlin
@SpringBootTest
class OrderIntegrationTest : IntegrationTestBase() {
    // Redis, Kafka, PostgreSQL 컨테이너가 자동으로 공유됨
    // @DynamicPropertySource로 연결 정보 자동 주입
}
```

---

## 7. application.yml 구조

모든 서비스가 공통으로 포함하는 설정:

```yaml
server:
  port: {서비스별 포트}
  shutdown: graceful

spring:
  application:
    name: {서비스명}
  lifecycle:
    timeout-per-shutdown-phase: 30s

  # Redis (gateway, queue-service, order-service, payment-service, notification-service)
  data:
    redis:
      host: localhost
      port: 6379

  # R2DBC (order-service, payment-service)
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/flashsale
    username: flashsale
    password: flashsale123

  # Flyway (order-service, payment-service)
  flyway:
    url: jdbc:postgresql://localhost:5432/flashsale  # JDBC URL (R2DBC 미지원)
    user: flashsale
    password: flashsale123

  # Kafka (order-service, payment-service, notification-service)
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      acks: all
      properties:
        enable.idempotence: true
    consumer:
      auto-offset-reset: earliest
      enable-auto-commit: false

  # Jackson (전체)
  jackson:
    serialization:
      write-dates-as-timestamps: false

# Actuator + Prometheus (전체)
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

---

## 8. 구현 순서

새 기능을 구현할 때 반드시 이 순서를 따릅니다 (CLAUDE.md 참조):

```
1. Domain       ─ Entity, Value Object, sealed interface Error
2. Port Out     ─ 아웃바운드 포트 인터페이스
3. Port In      ─ UseCase 인터페이스
4. UseCase      ─ 비즈니스 로직 구현 (suspend fun, withTimeout)
5. Adapter Out  ─ Redis/Kafka/DB 구현체
6. Adapter In   ─ Controller (suspend fun)
7. Config       ─ Spring 빈 등록 (필요 시)
8. Test         ─ 단위 테스트 → 통합 테스트
```

각 단계의 상세 가이드는 `docs/guides/` 디렉토리를 참조하세요:
- [새 API 엔드포인트 추가](guides/add-api-endpoint.md)
- [새 Kafka Consumer 추가](guides/add-kafka-consumer.md)
- [새 Redis 연산 추가](guides/add-redis-operation.md)
- [새 DB 엔티티 추가](guides/add-db-entity.md)
- [Resilience 패턴 적용](guides/add-resilience-pattern.md)
- [Saga 패턴 구현](guides/add-saga-pattern.md)
- [테스트 작성](guides/add-test.md)
