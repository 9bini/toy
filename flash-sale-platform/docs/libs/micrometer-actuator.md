# Micrometer + Spring Boot Actuator

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [Spring Boot Actuator](#3-spring-boot-actuator)
4. [Micrometer 메트릭 API](#4-micrometer-메트릭-api)
5. [이 프로젝트에서의 활용](#5-이-프로젝트에서의-활용)
6. [자주 하는 실수 / 주의사항](#6-자주-하는-실수--주의사항)
7. [정리 / 한눈에 보기](#7-정리--한눈에-보기)
8. [더 알아보기](#8-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

- **Spring Boot Actuator**: 서비스 상태(헬스, 메트릭, 환경)를 HTTP 엔드포인트로 노출
- **Micrometer**: 메트릭(숫자 데이터)을 수집하는 **계측 라이브러리** (Prometheus용 변환 포함)

### 관계

```
코드에서 메트릭 기록
    ↓
Micrometer (계측 API)
    ↓
micrometer-registry-prometheus (Prometheus 형식으로 변환)
    ↓
Actuator (/actuator/prometheus 엔드포인트로 노출)
    ↓
Prometheus가 수집 (Pull)
    ↓
Grafana가 시각화
```

---

## 2. 왜 필요한가?

### Actuator: 서비스 상태를 외부에서 확인

```
# 서비스가 살아있는지?
GET /actuator/health → {"status": "UP"}

# 현재 메트릭 값은?
GET /actuator/prometheus → http_server_requests_seconds_count{...} 15234
```

### Micrometer: 모니터링 시스템과의 연동

Micrometer는 **SLF4J의 메트릭 버전**이다.
코드에서 한 번 작성하면 Prometheus, Datadog, CloudWatch 등 다양한 시스템에 메트릭을 보낼 수 있다.

```kotlin
// Micrometer API (한 번 작성)
counter.increment()

// 출력은 Registry에 따라 달라짐
// → Prometheus: http_requests_total 15234
// → Datadog: http.requests.total 15234
// → CloudWatch: HttpRequests 15234
```

---

## 3. Spring Boot Actuator

### 주요 엔드포인트

| 엔드포인트 | 설명 |
|-----------|------|
| `/actuator/health` | 서비스 + 의존성(DB, Redis) 상태 |
| `/actuator/prometheus` | Prometheus 형식 메트릭 전체 |
| `/actuator/info` | 앱 정보 (버전, 빌드 등) |
| `/actuator/env` | 환경 변수 (주의: 민감 정보) |
| `/actuator/metrics` | 메트릭 목록 |
| `/actuator/metrics/{name}` | 특정 메트릭 상세 |

### 설정

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus  # 이 엔드포인트만 노출 (보안)
  endpoint:
    health:
      show-details: always           # DB, Redis 상태도 표시
```

### /actuator/health 응답 예시

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "PostgreSQL" } },
    "redis": { "status": "UP" },
    "kafka": { "status": "UP" },
    "diskSpace": { "status": "UP" }
  }
}
```

---

## 4. Micrometer 메트릭 API

### 4.1 Counter (카운터)

단조 증가하는 값. 총 요청 수, 총 에러 수 등.

```kotlin
@Component
class OrderMetrics(private val meterRegistry: MeterRegistry) {

    private val orderCounter = Counter.builder("orders.placed.total")
        .description("총 주문 접수 수")
        .tag("service", "order-service")
        .register(meterRegistry)

    fun recordOrderPlaced() {
        orderCounter.increment()
    }
}
```

### 4.2 Timer (타이머)

작업의 소요 시간을 측정. 횟수와 합계 시간 모두 기록.

```kotlin
private val orderTimer = Timer.builder("orders.processing.duration")
    .description("주문 처리 소요 시간")
    .register(meterRegistry)

suspend fun processOrder(order: Order) {
    orderTimer.record {
        // 이 블록의 실행 시간 측정
        actualProcessOrder(order)
    }
}
```

### 4.3 Gauge (게이지)

현재 값을 나타냄. 메모리 사용량, 활성 연결 수 등.

```kotlin
// AtomicInteger와 연동
private val activeConnections = AtomicInteger(0)

init {
    Gauge.builder("connections.active", activeConnections) { it.get().toDouble() }
        .description("활성 연결 수")
        .register(meterRegistry)
}
```

### 4.4 자동 수집되는 메트릭

Spring Boot가 자동으로 수집하는 메트릭 (코드 작성 불필요):

| 메트릭 | 내용 |
|--------|------|
| `http_server_requests_seconds` | HTTP 요청 수 + 응답 시간 |
| `jvm_memory_used_bytes` | JVM 메모리 사용량 |
| `jvm_threads_live_threads` | 활성 스레드 수 |
| `jvm_gc_pause_seconds` | GC 일시 정지 시간 |
| `process_cpu_usage` | CPU 사용률 |

---

## 5. 이 프로젝트에서의 활용

### 의존성

```kotlin
// 서비스별 build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-actuator")
implementation("io.micrometer:micrometer-registry-prometheus")
```

### Prometheus 연동 흐름

```
1. 서비스 시작 → Actuator가 /actuator/prometheus 엔드포인트 생성
2. Prometheus가 15초마다 해당 엔드포인트를 호출 (Pull)
3. 수집된 메트릭을 Prometheus 시계열 DB에 저장
4. Grafana가 Prometheus에서 데이터를 조회하여 시각화
```

---

## 6. 자주 하는 실수 / 주의사항

### 모든 엔드포인트 노출

```yaml
# ❌ 모든 엔드포인트 노출 (env, configprops 등 민감 정보 포함)
management.endpoints.web.exposure.include: "*"

# ✅ 필요한 것만 노출
management.endpoints.web.exposure.include: health, prometheus
```

### Counter를 매번 생성

```kotlin
// ❌ 메서드 호출마다 Counter 생성 → 메모리 누수
fun handle() {
    Counter.builder("requests").register(registry).increment()
}

// ✅ 필드로 한 번만 생성
private val counter = Counter.builder("requests").register(registry)
fun handle() {
    counter.increment()
}
```

---

## 7. 정리 / 한눈에 보기

| 구성 요소 | 역할 |
|----------|------|
| Actuator | 서비스 상태 HTTP 엔드포인트 노출 |
| Micrometer | 메트릭 계측 API (Counter, Timer, Gauge) |
| micrometer-registry-prometheus | Prometheus 형식 변환 |
| `/actuator/health` | 서비스 생존 확인 |
| `/actuator/prometheus` | 전체 메트릭 텍스트 |

---

## 8. 더 알아보기

- [Spring Boot Actuator 공식 문서](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Micrometer 공식 문서](https://micrometer.io/docs)
