# Jackson

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [핵심 개념](#3-핵심-개념)
4. [기본 사용법](#4-기본-사용법)
5. [이 프로젝트의 2개 모듈](#5-이-프로젝트의-2개-모듈)
6. [자주 하는 실수 / 주의사항](#6-자주-하는-실수--주의사항)
7. [정리 / 한눈에 보기](#7-정리--한눈에-보기)
8. [더 알아보기](#8-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

**JSON 문자열 ↔ Kotlin/Java 객체** 간 변환(직렬화/역직렬화)을 수행하는 라이브러리.

### 비유

- **직렬화**: 택배 포장 — 물건(객체)을 상자(JSON)에 넣어서 보냄
- **역직렬화**: 택배 개봉 — 상자(JSON)에서 물건(객체)을 꺼냄

---

## 2. 왜 필요한가?

HTTP, Kafka 등에서 데이터를 주고받을 때 **JSON 형식**이 표준이다.
Kotlin 객체를 JSON으로, JSON을 Kotlin 객체로 변환해야 한다.

```
HTTP 요청: {"orderId": "order-1", "quantity": 2}   → OrderRequest 객체
HTTP 응답: OrderResponse 객체 → {"status": "OK"}
Kafka:    OrderPlacedEvent 객체 ↔ JSON 문자열
```

---

## 3. 핵심 개념

### 3.1 ObjectMapper

Jackson의 핵심 클래스. 모든 변환을 수행한다.

```kotlin
val objectMapper = ObjectMapper()

// 직렬화: 객체 → JSON
val json: String = objectMapper.writeValueAsString(order)

// 역직렬화: JSON → 객체
val order: Order = objectMapper.readValue(json, Order::class.java)
```

### 3.2 직렬화 (Serialization)

```kotlin
data class Order(val id: String, val status: String, val quantity: Int)

val order = Order("order-1", "PENDING", 2)
val json = objectMapper.writeValueAsString(order)
// → {"id":"order-1","status":"PENDING","quantity":2}
```

### 3.3 역직렬화 (Deserialization)

```kotlin
val json = """{"id":"order-1","status":"PENDING","quantity":2}"""
val order = objectMapper.readValue<Order>(json)  // Kotlin 확장 함수
// → Order(id="order-1", status="PENDING", quantity=2)
```

---

## 4. 기본 사용법

### Spring Boot에서는 자동 등록

```kotlin
// Spring Boot가 ObjectMapper를 자동으로 빈 등록
// @RequestBody, @ResponseBody에서 자동 변환

@PostMapping("/orders")
suspend fun create(@RequestBody request: OrderRequest): OrderResponse {
    //                ↑ JSON → OrderRequest 자동 변환
    return OrderResponse(...)
    //     ↑ OrderResponse → JSON 자동 변환
}
```

### @JsonProperty (필드 이름 매핑)

```kotlin
data class Order(
    @JsonProperty("order_id") val orderId: String,  // JSON: "order_id" ↔ Kotlin: orderId
    val status: String
)
// JSON: {"order_id": "123", "status": "OK"}
```

### @JsonIgnore (필드 제외)

```kotlin
data class User(
    val name: String,
    @JsonIgnore val password: String  // JSON에 포함 안 됨
)
```

---

## 5. 이 프로젝트의 2개 모듈

### 5.1 jackson-module-kotlin

Kotlin data class를 정상적으로 역직렬화하기 위한 모듈.

**문제**: Kotlin data class는 기본 생성자가 없다.

```kotlin
// Java 클래스: 기본 생성자 있음
class Order {
    String id;
    String status;
    Order() {}  // ← Jackson이 이걸로 객체 생성
}

// Kotlin data class: 기본 생성자 없음!
data class Order(val id: String, val status: String)
// Jackson 기본: "Cannot construct instance... no Creators" 에러!
```

**해결**: jackson-module-kotlin을 등록하면 Kotlin 주 생성자로 역직렬화.

### 5.2 jackson-datatype-jsr310

Java 8 날짜/시간 타입 (`Instant`, `LocalDateTime`)을 올바르게 처리하는 모듈.

**문제**: 기본 Jackson은 Instant를 이상하게 직렬화한다.

```kotlin
data class Order(val createdAt: Instant)

// jsr310 없이:
// {"createdAt": {"epochSecond": 1708900000, "nano": 0}}   ← 읽기 어려움

// jsr310 있으면:
// {"createdAt": "2026-02-25T10:00:00Z"}   ← ISO 8601 (표준)
```

### 의존성

```kotlin
// common/infrastructure/build.gradle.kts
api("com.fasterxml.jackson.module:jackson-module-kotlin")
api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
```

Spring Boot가 두 모듈을 자동 감지하여 ObjectMapper에 등록한다.

---

## 6. 자주 하는 실수 / 주의사항

### 모듈 미등록

```
// ❌ jackson-module-kotlin 없이 data class 역직렬화
// → "Cannot construct instance of Order (no Creators)"

// ✅ 의존성 추가만 하면 Spring Boot가 자동 등록
implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
```

### Unknown properties 에러

```kotlin
// JSON에 Kotlin 클래스에 없는 필드가 있을 때
// {"id": "1", "status": "OK", "extra": "unknown"}
// → "Unrecognized field 'extra'" 에러

// 해결 1: 클래스에 @JsonIgnoreProperties 추가
@JsonIgnoreProperties(ignoreUnknown = true)
data class Order(val id: String, val status: String)

// 해결 2: ObjectMapper 설정 (Spring Boot 기본 설정으로 이미 처리됨)
```

---

## 7. 정리 / 한눈에 보기

| 모듈 | 해결하는 문제 |
|------|-------------|
| `jackson-module-kotlin` | data class 기본 생성자 없는 문제 |
| `jackson-datatype-jsr310` | Instant 등 날짜 타입 직렬화 형식 |

| 사용처 | 변환 |
|--------|------|
| WebFlux Controller | `@RequestBody` / `@ResponseBody` 자동 변환 |
| Kafka | 이벤트 객체 ↔ JSON 문자열 |
| Redis | 복합 객체 저장 시 JSON 변환 |

---

## 8. 더 알아보기

- [Jackson 공식 GitHub](https://github.com/FasterXML/jackson)
- [jackson-module-kotlin](https://github.com/FasterXML/jackson-module-kotlin)
