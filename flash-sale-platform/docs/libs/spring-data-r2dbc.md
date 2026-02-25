# Spring Data R2DBC

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [핵심 개념](#3-핵심-개념)
4. [기본 사용법](#4-기본-사용법)
5. [이 프로젝트에서의 활용](#5-이-프로젝트에서의-활용)
6. [자주 하는 실수 / 주의사항](#6-자주-하는-실수--주의사항)
7. [정리 / 한눈에 보기](#7-정리--한눈에-보기)
8. [더 알아보기](#8-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

**논블로킹**으로 관계형 DB에 접근하는 리액티브 데이터 접근 라이브러리.
JDBC의 비동기 버전이라고 생각하면 된다.

### 비유

- **JDBC (블로킹)**: 은행 창구에서 번호표 뽑고 앉아서 기다림 (자리 점유)
- **R2DBC (논블로킹)**: 번호표 뽑고 다른 일 하다가, 호출되면 가서 처리

---

## 2. 왜 필요한가?

WebFlux는 논블로킹인데, 기존 JDBC는 블로킹이다.
DB 호출에서 스레드가 블로킹되면 WebFlux의 장점이 사라진다.

```
WebFlux + JDBC (잘못된 조합):
  스레드 8개 → DB 호출 시 블로킹 → 8개 요청 처리하면 스레드 고갈

WebFlux + R2DBC (올바른 조합):
  스레드 8개 → DB 호출 시 스레드 반납 → 수만 요청 처리 가능
```

### 비교 표

| | Spring Data JPA (JDBC) | Spring Data R2DBC |
|---|---|---|
| I/O | 블로킹 | **논블로킹** |
| 반환 타입 | `Optional<T>`, `List<T>` | `Mono<T>`, `Flux<T>` |
| ORM | Hibernate (강력) | **없음** (직접 매핑) |
| 관계 매핑 | `@OneToMany`, `@ManyToOne` | **미지원** |
| Lazy Loading | 지원 | **미지원** |
| 호환 프레임워크 | Spring MVC | **Spring WebFlux** |
| 학습 곡선 | 낮음 | 중간 |

---

## 3. 핵심 개념

### 3.1 엔티티 (Entity)

DB 테이블의 한 행(Row)을 Kotlin 객체로 매핑한다.

```kotlin
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("orders")                           // 테이블 이름
data class OrderEntity(
    @Id val id: String,                    // Primary Key
    @Column("user_id") val userId: String, // 열 이름 매핑 (camelCase → snake_case)
    @Column("product_id") val productId: String,
    val quantity: Int,                     // 열 이름이 같으면 @Column 생략 가능
    val status: String,
    @Column("created_at") val createdAt: Instant,
)
```

> **주의**: JPA의 `@Entity`가 아닌 Spring Data의 `@Table` 사용

### 3.2 Repository (저장소)

인터페이스만 정의하면 Spring이 **자동으로 구현**해준다.

```kotlin
interface OrderRepository : ReactiveCrudRepository<OrderEntity, String> {
    // 메서드명 → 쿼리 자동 생성
    fun findByUserId(userId: String): Flux<OrderEntity>
    fun findByStatus(status: String): Flux<OrderEntity>
    fun findByUserIdAndStatus(userId: String, status: String): Flux<OrderEntity>

    // 커스텀 SQL
    @Query("SELECT * FROM orders WHERE sale_event_id = :saleEventId AND user_id = :userId")
    fun findBySaleEventAndUser(saleEventId: String, userId: String): Mono<OrderEntity>
}
```

### 메서드 이름 규칙

| 메서드명 | 생성되는 SQL |
|---------|-------------|
| `findByUserId(id)` | `WHERE user_id = :id` |
| `findByStatus(s)` | `WHERE status = :s` |
| `findByUserIdAndStatus(id, s)` | `WHERE user_id = :id AND status = :s` |
| `findByCreatedAtAfter(t)` | `WHERE created_at > :t` |
| `countByStatus(s)` | `SELECT COUNT(*) WHERE status = :s` |
| `existsByUserId(id)` | `SELECT EXISTS(... WHERE user_id = :id)` |

### 3.3 ReactiveCrudRepository 기본 메서드

```kotlin
// 저장 (INSERT or UPDATE)
repository.save(entity): Mono<T>

// ID로 조회
repository.findById(id): Mono<T>

// 전체 조회
repository.findAll(): Flux<T>

// 삭제
repository.deleteById(id): Mono<Void>
repository.delete(entity): Mono<Void>

// 존재 확인
repository.existsById(id): Mono<Boolean>

// 개수
repository.count(): Mono<Long>
```

### 3.4 코루틴에서 사용

```kotlin
// Mono → suspend 값
val order: OrderEntity? = orderRepository.findById(id).awaitSingleOrNull()

// Flux → Flow → List
val orders: List<OrderEntity> = orderRepository
    .findByUserId(userId)
    .asFlow()
    .toList()

// 저장
val saved: OrderEntity = orderRepository.save(entity).awaitSingle()
```

### 3.5 트랜잭션

```kotlin
@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val transactionalOperator: TransactionalOperator,
) {
    // 방법 1: @Transactional 어노테이션
    @Transactional
    suspend fun createOrder(order: OrderEntity): OrderEntity {
        return orderRepository.save(order).awaitSingle()
    }

    // 방법 2: 프로그래밍 방식
    suspend fun createOrderProgrammatic(order: OrderEntity): OrderEntity {
        return transactionalOperator.executeAndAwait {
            orderRepository.save(order).awaitSingle()
        }
    }
}
```

---

## 4. 기본 사용법

### 설정

```yaml
# application.yml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/flashsale
    username: flashsale
    password: flashsale123
```

```kotlin
// build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
runtimeOnly(libs.r2dbc.postgresql)
```

### CRUD 예시

```kotlin
@Service
class OrderService(
    private val orderRepository: OrderRepository,
) {
    // CREATE
    suspend fun create(order: OrderEntity): OrderEntity {
        return orderRepository.save(order).awaitSingle()
    }

    // READ
    suspend fun findById(id: String): OrderEntity? {
        return orderRepository.findById(id).awaitSingleOrNull()
    }

    // UPDATE (save로 동일하게 처리, @Id가 있으면 UPDATE)
    suspend fun updateStatus(id: String, newStatus: String): OrderEntity {
        val order = orderRepository.findById(id).awaitSingle()
        val updated = order.copy(status = newStatus)
        return orderRepository.save(updated).awaitSingle()
    }

    // DELETE
    suspend fun delete(id: String) {
        orderRepository.deleteById(id).awaitFirstOrNull()
    }
}
```

---

## 5. 이 프로젝트에서의 활용

### 의존성

```kotlin
// order-service, payment-service의 build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
runtimeOnly(libs.r2dbc.postgresql)
```

### 도메인 객체 ↔ 엔티티 변환

```kotlin
// 도메인 모델 (비즈니스 로직)
data class Order(val id: OrderId, val userId: UserId, val status: OrderStatus)

// DB 엔티티 (R2DBC 매핑)
@Table("orders")
data class OrderEntity(@Id val id: String, val userId: String, val status: String)

// 변환 함수
fun OrderEntity.toDomain() = Order(OrderId(id), UserId(userId), OrderStatus.valueOf(status))
fun Order.toEntity() = OrderEntity(id.value, userId.value, status.name)
```

---

## 6. 자주 하는 실수 / 주의사항

### JPA 어노테이션 사용

```kotlin
// ❌ JPA 어노테이션 (R2DBC에서 동작 안 함)
import javax.persistence.Entity
import javax.persistence.OneToMany

@Entity
class Order(@OneToMany val items: List<Item>)

// ✅ Spring Data 어노테이션
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.annotation.Id

@Table("orders")
data class OrderEntity(@Id val id: String)
```

### 관계 매핑 없음

```kotlin
// ❌ R2DBC는 관계 매핑 미지원
@Table("orders")
data class OrderEntity(
    @Id val id: String,
    val items: List<ItemEntity>,  // 컴파일은 되지만 동작 안 함
)

// ✅ 별도 쿼리로 조회
val order = orderRepo.findById(id).awaitSingle()
val items = itemRepo.findByOrderId(id).asFlow().toList()
```

### save()의 INSERT vs UPDATE 판단

```kotlin
// R2DBC는 @Id 필드로 판단:
//   - @Id가 null → INSERT (새로 생성)
//   - @Id가 not null → UPDATE (기존 수정)

// ⚠️ 우리 프로젝트는 id를 직접 생성하므로 (UUID)
//    새 엔티티도 @Id가 not null → UPDATE 시도 → 에러!

// 해결: @Id 필드에 @CreatedDate 사용하거나,
//       Persistable 인터페이스 구현
```

---

## 7. 정리 / 한눈에 보기

### JDBC vs R2DBC 요약

| 항목 | JDBC | R2DBC |
|------|------|-------|
| I/O | 블로킹 | 논블로킹 |
| 반환 | `T`, `List<T>` | `Mono<T>`, `Flux<T>` |
| ORM | Hibernate | 없음 |
| 관계 매핑 | `@OneToMany` 등 | 직접 쿼리 |
| 프레임워크 | Spring MVC | Spring WebFlux |

### Repository 메서드 → 코루틴 변환

| Repository 반환 | 코루틴 변환 | 결과 |
|----------------|-----------|------|
| `Mono<T>` | `.awaitSingle()` | `T` (없으면 예외) |
| `Mono<T>` | `.awaitSingleOrNull()` | `T?` |
| `Flux<T>` | `.asFlow().toList()` | `List<T>` |
| `Mono<Void>` | `.awaitFirstOrNull()` | `Unit` |

---

## 8. 더 알아보기

- [Spring Data R2DBC 공식 문서](https://docs.spring.io/spring-data/r2dbc/docs/current/reference/html/)
- [R2DBC 스펙](https://r2dbc.io/)
