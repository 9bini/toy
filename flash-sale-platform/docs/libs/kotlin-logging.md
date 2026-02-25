# kotlin-logging + Logstash Logback Encoder

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [핵심 개념](#3-핵심-개념)
4. [기본 사용법](#4-기본-사용법)
5. [MDC (로깅 컨텍스트)](#5-mdc-로깅-컨텍스트)
6. [JSON 로그 (logstash-logback-encoder)](#6-json-로그-logstash-logback-encoder)
7. [이 프로젝트에서의 활용](#7-이-프로젝트에서의-활용)
8. [자주 하는 실수 / 주의사항](#8-자주-하는-실수--주의사항)
9. [정리 / 한눈에 보기](#9-정리--한눈에-보기)
10. [더 알아보기](#10-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

- **kotlin-logging**: Kotlin에 최적화된 로깅 API (SLF4J 래퍼)
- **logstash-logback-encoder**: 로그를 JSON 형식으로 출력하는 인코더

### 로깅 생태계 구조

```
애플리케이션 코드
    ↓ (API 호출)
kotlin-logging (편의 API)
    ↓
SLF4J (로깅 표준 인터페이스)
    ↓
Logback (로깅 구현체, Spring Boot 기본)
    ↓
출력: 콘솔, 파일, JSON...
```

---

## 2. 왜 필요한가?

### 디버깅의 기본

```
println("주문 처리 시작")      ← 운영 환경에서 사용 불가
logger.info { "주문 처리 시작" }  ← 레벨 제어, 파일 저장, 구조화 가능
```

### Java 로깅 vs kotlin-logging

```kotlin
// ❌ Java 스타일 (장황함)
private val logger = LoggerFactory.getLogger(OrderService::class.java)
logger.info("Order placed: " + orderId)  // 문자열 연결 항상 실행

// ✅ kotlin-logging (간결, 성능 좋음)
private val logger = KotlinLogging.logger {}
logger.info { "Order placed: $orderId" }  // 람다: INFO 레벨일 때만 실행
```

---

## 3. 핵심 개념

### 3.1 로그 레벨 (5단계)

```
TRACE   가장 상세 (변수 값 하나하나)          ← 개발 디버깅
DEBUG   디버깅 정보 (흐름 추적)              ← 개발 환경
INFO    일반 동작 정보 (시작, 완료)           ← 운영 기본
WARN    경고 (정상이지만 주의 필요)           ← 항상 확인
ERROR   에러 (정상 동작 실패)                ← 항상 확인
```

**레벨 설정 원리**: 설정한 레벨 이상만 출력된다.

```yaml
# application.yml
logging:
  level:
    root: INFO               # 기본: INFO 이상만 출력
    com.flashsale: DEBUG     # 우리 코드: DEBUG 이상 출력
    org.springframework: WARN # Spring: WARN 이상만 출력
```

```
logging.level = INFO 일 때:
  TRACE → 출력 안 됨
  DEBUG → 출력 안 됨
  INFO  → ✅ 출력
  WARN  → ✅ 출력
  ERROR → ✅ 출력
```

### 3.2 람다를 쓰는 이유 (성능)

```kotlin
// ❌ 문자열이 항상 생성됨 (DEBUG가 꺼져 있어도)
logger.debug("Heavy object: ${heavyObject.toDetailString()}")
// toDetailString()이 항상 실행됨!

// ✅ DEBUG 레벨일 때만 람다 실행
logger.debug { "Heavy object: ${heavyObject.toDetailString()}" }
// DEBUG가 꺼져 있으면 → 람다 자체가 실행 안 됨 → 성능 이점
```

---

## 4. 기본 사용법

```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}  // 클래스 상단에 선언

class OrderService {

    fun placeOrder(orderId: String) {
        logger.info { "주문 처리 시작: $orderId" }

        try {
            // 비즈니스 로직
            logger.debug { "재고 확인 완료: productId=$productId" }
            logger.trace { "상세 데이터: $data" }
        } catch (e: Exception) {
            logger.error(e) { "주문 처리 실패: $orderId, 원인: ${e.message}" }
            //       ↑ 예외 객체 전달 → 스택 트레이스 출력
            throw e
        }

        logger.info { "주문 처리 완료: $orderId" }
    }
}
```

### 로그 출력 예시

```
2026-02-25 10:00:00.123 INFO  [order-service] c.f.o.OrderService - 주문 처리 시작: order-123
2026-02-25 10:00:00.125 DEBUG [order-service] c.f.o.OrderService - 재고 확인 완료: productId=prod-1
2026-02-25 10:00:00.130 INFO  [order-service] c.f.o.OrderService - 주문 처리 완료: order-123
```

---

## 5. MDC (로깅 컨텍스트)

### MDC란?

**Mapped Diagnostic Context** — 요청별로 고유한 정보(요청 ID, 사용자 ID)를 로그에 자동으로 포함.

```
MDC 없이:
  INFO 주문 처리 시작
  INFO 결제 요청
  INFO 주문 처리 시작     ← 어떤 요청의 로그인지 구분 불가!
  INFO 결제 완료

MDC 있으면:
  INFO [req-abc] 주문 처리 시작
  INFO [req-abc] 결제 요청
  INFO [req-xyz] 주문 처리 시작     ← req-abc와 req-xyz 구분 가능!
  INFO [req-abc] 결제 완료
```

### 코루틴에서 MDC 전파

코루틴은 스레드를 전환하므로, MDC가 사라질 수 있다.
`kotlinx-coroutines-slf4j` 모듈이 코루틴 간 MDC를 전파한다.

```kotlin
// kotlinx-coroutines-slf4j 의존성 필요
withContext(MDCContext()) {
    // 이 블록 안에서 MDC가 유지됨 (스레드 전환되어도)
    logger.info { "MDC 유지됨" }
}
```

---

## 6. JSON 로그 (logstash-logback-encoder)

### 왜 JSON 로그?

```
// 텍스트 로그: 사람이 읽기 좋지만 기계가 파싱하기 어려움
2026-02-25 10:00:00 INFO [order-service] 주문 처리 시작: order-123

// JSON 로그: 기계가 파싱하기 쉬움 (ELK Stack, CloudWatch 등)
{"timestamp":"2026-02-25T10:00:00","level":"INFO","logger":"OrderService","message":"주문 처리 시작: order-123","service":"order-service","orderId":"order-123"}
```

### 설정 (logback-spring.xml)

```xml
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <customFields>{"service":"order-service"}</customFields>
    </encoder>
  </appender>

  <root level="INFO">
    <appender-ref ref="JSON"/>
  </root>
</configuration>
```

---

## 7. 이 프로젝트에서의 활용

### 의존성

```kotlin
// build.gradle.kts (루트 subprojects)
implementation(rootProject.libs.kotlin.logging)         // 7.0.3

// common/infrastructure/build.gradle.kts
implementation(libs.logstash.logback.encoder)            // 8.0

// 코루틴 MDC 전파
implementation(rootProject.libs.bundles.coroutines)      // coroutines-slf4j 포함
```

---

## 8. 자주 하는 실수 / 주의사항

### 로그에 민감 정보 포함

```kotlin
// ❌ 비밀번호, 토큰 등 로그에 노출
logger.info { "User login: email=${user.email}, password=${user.password}" }

// ✅ 민감 정보 마스킹 또는 제외
logger.info { "User login: email=${user.email}" }
```

### ERROR 레벨 남용

```kotlin
// ❌ 비즈니스 실패(재고 부족)에 ERROR 사용
logger.error { "재고 부족" }  // 정상적인 흐름인데 에러?

// ✅ 비즈니스 실패는 WARN, 시스템 오류만 ERROR
logger.warn { "재고 부족: productId=$productId" }
logger.error(e) { "DB 연결 실패" }  // 진짜 에러
```

---

## 9. 정리 / 한눈에 보기

| 로그 레벨 | 용도 | 예시 |
|----------|------|------|
| TRACE | 극히 상세 | 변수 값 하나하나 |
| DEBUG | 개발 디버깅 | 함수 진입/퇴장, 중간 값 |
| INFO | 일반 동작 | 시작/완료, 주요 이벤트 |
| WARN | 경고 | 비즈니스 실패, 성능 저하 |
| ERROR | 에러 | 예외 발생, 시스템 오류 |

| 라이브러리 | 역할 |
|-----------|------|
| `kotlin-logging` | Kotlin 친화 로깅 API |
| `SLF4J` | 로깅 표준 인터페이스 |
| `Logback` | 실제 로그 출력 구현체 |
| `logstash-logback-encoder` | JSON 형식 로그 |
| `coroutines-slf4j` | 코루틴 MDC 전파 |

---

## 10. 더 알아보기

- [kotlin-logging GitHub](https://github.com/oshai/kotlin-logging)
- [Logback 공식 문서](https://logback.qos.ch/documentation.html)
- [logstash-logback-encoder GitHub](https://github.com/logfellow/logstash-logback-encoder)
