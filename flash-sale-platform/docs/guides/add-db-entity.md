# 새 DB 엔티티 + Flyway 마이그레이션 가이드

> order-service의 **orders 테이블**을 예제로 Step-by-Step 따라하기

---

## 목차

1. [전체 흐름](#1-전체-흐름)
2. [Step 1: Flyway 마이그레이션 파일](#step-1-flyway-마이그레이션-파일)
3. [Step 2: R2DBC 엔티티](#step-2-r2dbc-엔티티)
4. [Step 3: Repository](#step-3-repository)
5. [Step 4: 아웃바운드 Port](#step-4-아웃바운드-port)
6. [Step 5: Adapter 구현](#step-5-adapter-구현)
7. [Step 6: application.yml 설정](#step-6-applicationyml-설정)
8. [Step 7: 테스트](#step-7-테스트)
9. [자주 하는 실수](#자주-하는-실수)

---

## 1. 전체 흐름

```
1. Flyway SQL 작성    → 테이블 생성 (서버 시작 시 자동 실행)
2. R2DBC 엔티티      → Kotlin data class (@Table)
3. Repository       → CoroutineCrudRepository 상속
4. Port 인터페이스    → 기술 세부사항 숨김
5. Adapter 구현      → 엔티티 ↔ 도메인 모델 변환
```

---

## Step 1: Flyway 마이그레이션 파일

### 파일 위치

```
services/order-service/src/main/resources/db/migration/V1__create_orders_table.sql
```

### 파일 네이밍 규칙

```
V{버전}__{설명}.sql

V  → 접두사 (필수, 대문자)
{버전} → 1, 2, 3... (순차적)
__ → 언더스코어 2개 (필수)
{설명} → snake_case 설명
.sql → 확장자

예시:
V1__create_orders_table.sql       ✅
V2__add_payment_id_to_orders.sql  ✅
V3__create_payments_table.sql     ✅

V1_create_orders.sql              ❌ (언더스코어 1개)
V1-create-orders.sql              ❌ (하이픈 사용)
```

### SQL 작성

```sql
-- V1__create_orders_table.sql

CREATE TABLE orders (
    id          VARCHAR(26)  PRIMARY KEY,  -- ULID (IdGenerator 기반)
    user_id     VARCHAR(26)  NOT NULL,
    product_id  VARCHAR(26)  NOT NULL,
    quantity    INTEGER      NOT NULL CHECK (quantity > 0),
    status      VARCHAR(20)  NOT NULL DEFAULT 'CREATED',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT orders_status_check CHECK (
        status IN ('CREATED', 'STOCK_RESERVED', 'PAYMENT_PENDING', 'COMPLETED', 'CANCELLED')
    )
);

-- 인덱스 (조회 패턴에 맞게)
CREATE INDEX idx_orders_user_id ON orders (user_id);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_created_at ON orders (created_at DESC);
```

### 컬럼 추가 마이그레이션

```sql
-- V2__add_payment_id_to_orders.sql

ALTER TABLE orders ADD COLUMN payment_id VARCHAR(26);
CREATE INDEX idx_orders_payment_id ON orders (payment_id);
```

### 절대 금지 사항

- 한 번 적용된 마이그레이션 파일을 **수정하지 않는다** (체크섬 불일치 에러)
- 번호를 **건너뛰지 않는다** (V1 → V3 ❌)
- 개발 중 수정이 필요하면 **새 마이그레이션 파일**을 추가한다

---

## Step 2: R2DBC 엔티티

```kotlin
package com.flashsale.order.adapter.out.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.relational.core.mapping.Column
import java.time.Instant

@Table("orders")
data class OrderEntity(
    @Id
    val id: String,                          // VARCHAR(26) — ULID

    @Column("user_id")
    val userId: String,

    @Column("product_id")
    val productId: String,

    val quantity: Int,

    val status: String,                      // enum 이름 문자열

    @Column("created_at")
    val createdAt: Instant,

    @Column("updated_at")
    val updatedAt: Instant = Instant.now(),
)
```

### 엔티티 규칙

| 규칙 | 설명 |
|------|------|
| `@Table("테이블명")` | DB 테이블 매핑 |
| `@Id` | 기본 키 (자동 생성 아님 — ULID 직접 할당) |
| `@Column("컬럼명")` | camelCase ↔ snake_case 매핑 |
| data class | 불변 객체 (var 대신 val) |
| Instant | 날짜/시간은 항상 `java.time.Instant` |

### JPA(@Entity)와의 차이

```kotlin
// ❌ JPA 스타일 (R2DBC에서 사용 불가)
@Entity
@GeneratedValue(strategy = GenerationType.IDENTITY)
var id: Long? = null

// ✅ R2DBC 스타일
@Table("orders")
data class OrderEntity(
    @Id val id: String,  // 직접 할당 (ULID)
)
```

---

## Step 3: Repository

```kotlin
package com.flashsale.order.adapter.out.persistence

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * CoroutineCrudRepository: 코루틴 기반 CRUD (suspend fun 자동 제공)
 * - save(entity): 저장/수정
 * - findById(id): ID로 조회
 * - deleteById(id): ID로 삭제
 * - findAll(): 전체 조회
 * - count(): 개수
 */
interface OrderRepository : CoroutineCrudRepository<OrderEntity, String> {

    /** 사용자별 주문 조회 (메서드 이름으로 쿼리 생성) */
    suspend fun findByUserId(userId: String): List<OrderEntity>

    /** 상태별 주문 조회 */
    suspend fun findByStatus(status: String): List<OrderEntity>

    /** 커스텀 쿼리: 상태 업데이트 */
    @Modifying
    @Query("UPDATE orders SET status = :status, updated_at = NOW() WHERE id = :id")
    suspend fun updateStatus(id: String, status: String): Int

    /** 커스텀 쿼리: 사용자의 특정 상품 주문 존재 확인 */
    @Query("SELECT COUNT(*) > 0 FROM orders WHERE user_id = :userId AND product_id = :productId AND status != 'CANCELLED'")
    suspend fun existsActiveOrder(userId: String, productId: String): Boolean
}
```

### 쿼리 메서드 네이밍 규칙

```
findBy{필드}          → WHERE 필드 = ?
findBy{필드}And{필드}  → WHERE 필드1 = ? AND 필드2 = ?
findBy{필드}OrderBy{필드}Desc → ORDER BY 필드 DESC
countBy{필드}         → COUNT WHERE 필드 = ?
existsBy{필드}        → EXISTS WHERE 필드 = ?
deleteBy{필드}        → DELETE WHERE 필드 = ?
```

---

## Step 4: 아웃바운드 Port

> 규칙: Repository, Entity, R2DBC 등 기술 세부사항을 노출하지 않는다.

```kotlin
package com.flashsale.order.application.port.out

import com.flashsale.order.domain.model.Order
import com.flashsale.order.domain.model.OrderStatus

/** 주문 영속화 포트 — 어떤 DB를 쓰는지 알 수 없다 */
interface OrderPersistencePort {
    suspend fun save(order: Order): Order
    suspend fun findById(orderId: String): Order?
    suspend fun findByUserId(userId: String): List<Order>
    suspend fun updateStatus(orderId: String, status: OrderStatus)
    suspend fun existsActiveOrder(userId: String, productId: String): Boolean
}
```

---

## Step 5: Adapter 구현

> 도메인 모델 ↔ 엔티티 변환을 여기서 수행한다.

```kotlin
package com.flashsale.order.adapter.out.persistence

import com.flashsale.order.application.port.out.OrderPersistencePort
import com.flashsale.order.domain.model.Order
import com.flashsale.order.domain.model.OrderStatus
import org.springframework.stereotype.Component

@Component
class R2dbcOrderAdapter(
    private val repository: OrderRepository,
) : OrderPersistencePort {

    override suspend fun save(order: Order): Order {
        repository.save(order.toEntity())
        return order
    }

    override suspend fun findById(orderId: String): Order? =
        repository.findById(orderId)?.toDomain()

    override suspend fun findByUserId(userId: String): List<Order> =
        repository.findByUserId(userId).map { it.toDomain() }

    override suspend fun updateStatus(orderId: String, status: OrderStatus) {
        repository.updateStatus(orderId, status.name)
    }

    override suspend fun existsActiveOrder(userId: String, productId: String): Boolean =
        repository.existsActiveOrder(userId, productId)

    // === 변환 함수 ===

    private fun Order.toEntity() = OrderEntity(
        id = id,
        userId = userId,
        productId = productId,
        quantity = quantity,
        status = status.name,
        createdAt = createdAt,
    )

    private fun OrderEntity.toDomain() = Order(
        id = id,
        userId = userId,
        productId = productId,
        quantity = quantity,
        status = OrderStatus.valueOf(status),
        createdAt = createdAt,
    )
}
```

---

## Step 6: application.yml 설정

### R2DBC + Flyway 이중 설정이 필요한 이유

```
R2DBC  → 비동기 DB 드라이버 (애플리케이션에서 사용)
Flyway → 마이그레이션 (JDBC만 지원, R2DBC 미지원)

둘 다 설정해야 함!
```

```yaml
spring:
  # R2DBC (비동기 — 애플리케이션 코드에서 사용)
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/flashsale
    username: flashsale
    password: flashsale123
    pool:
      initial-size: 5
      max-size: 20
      max-idle-time: 30m
      validation-query: SELECT 1

  # Flyway (JDBC — 마이그레이션 전용)
  flyway:
    url: jdbc:postgresql://localhost:5432/flashsale   # ← jdbc:// (R2DBC 아님!)
    user: flashsale
    password: flashsale123
    locations: classpath:db/migration
```

---

## Step 7: 테스트

### 통합 테스트 (Testcontainers PostgreSQL)

```kotlin
@SpringBootTest
class R2dbcOrderAdapterTest : IntegrationTestBase(), FunSpec({
    val adapter = autowired<R2dbcOrderAdapter>()

    test("주문을 저장하고 조회할 수 있다") {
        // given
        val order = Order.create(
            userId = "user-1",
            productId = "prod-1",
            quantity = 2,
        )

        // when
        adapter.save(order)
        val found = adapter.findById(order.id)

        // then
        found shouldNotBe null
        found!!.userId shouldBe "user-1"
        found.productId shouldBe "prod-1"
        found.quantity shouldBe 2
        found.status shouldBe OrderStatus.CREATED
    }

    test("상태를 업데이트할 수 있다") {
        val order = Order.create("user-1", "prod-1", 1)
        adapter.save(order)

        adapter.updateStatus(order.id, OrderStatus.COMPLETED)

        val found = adapter.findById(order.id)
        found!!.status shouldBe OrderStatus.COMPLETED
    }

    test("사용자별 주문을 조회할 수 있다") {
        val order1 = Order.create("user-1", "prod-1", 1)
        val order2 = Order.create("user-1", "prod-2", 1)
        val order3 = Order.create("user-2", "prod-1", 1)
        adapter.save(order1)
        adapter.save(order2)
        adapter.save(order3)

        val orders = adapter.findByUserId("user-1")

        orders.size shouldBe 2
    }
})
```

### IntegrationTestBase가 자동으로 처리하는 것

```kotlin
// Redis, Kafka, PostgreSQL Testcontainers 싱글턴 시작
// @DynamicPropertySource로 연결 정보 자동 주입:
//   spring.r2dbc.url → Testcontainers PostgreSQL
//   spring.flyway.url → Testcontainers PostgreSQL (JDBC)
//   spring.data.redis.host/port → Testcontainers Redis
//   spring.kafka.bootstrap-servers → Testcontainers Kafka
```

---

## 자주 하는 실수

### 1. Flyway 파일 수정

```
❌ V1__create_orders_table.sql을 수정
→ FlywayException: Migration checksum mismatch

✅ 새 파일로 변경사항 추가
→ V2__alter_orders_add_column.sql
```

### 2. R2DBC URL 형식

```
❌ url: jdbc:postgresql://...    (Flyway용 URL을 R2DBC에 사용)
✅ url: r2dbc:postgresql://...   (R2DBC 전용 프로토콜)
```

### 3. @Id에 자동 생성 사용

```kotlin
// ❌ R2DBC는 @GeneratedValue 미지원 (JPA 전용)
@Id @GeneratedValue val id: Long? = null

// ✅ ULID 직접 할당
@Id val id: String  // IdGenerator.generate()
```

### 4. 블로킹 JDBC 사용

```kotlin
// ❌ R2DBC 환경에서 JDBC 사용 → 이벤트 루프 블로킹
val conn = DriverManager.getConnection(jdbcUrl)
conn.prepareStatement("SELECT * FROM orders").executeQuery()

// ✅ CoroutineCrudRepository 사용
val orders = repository.findAll()
```

### 5. snake_case ↔ camelCase 매핑 누락

```kotlin
// ❌ 컬럼명 불일치
data class OrderEntity(
    val userId: String,  // DB 컬럼은 user_id → 매핑 실패!
)

// ✅ @Column으로 명시적 매핑
data class OrderEntity(
    @Column("user_id") val userId: String,
)
```
