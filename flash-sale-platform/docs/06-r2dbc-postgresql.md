# 6. R2DBC + PostgreSQL + Flyway

> **한 줄 요약**: 비동기(논블로킹)로 PostgreSQL에 접근하는 리액티브 데이터베이스 연결 + 스키마 자동 관리

---

## 왜 R2DBC인가?

### 전통적인 JDBC의 문제

```
요청 → 스레드 할당 → JDBC 쿼리 실행 → [스레드가 DB 응답 대기 중...] → 응답
                                         ↑ 이 시간 동안 스레드 낭비
```

- JDBC는 **블로킹**: DB 응답을 기다리는 동안 스레드를 점유
- WebFlux의 소수 스레드 모델과 **충돌** → 스레드 풀 고갈

### R2DBC의 해결

```
요청 → 스레드 할당 → R2DBC 쿼리 발송 → 스레드 반납 → (다른 요청 처리)
                                          ...DB 응답 도착...
                                    → 스레드 재할당 → 응답 처리
```

- R2DBC는 **논블로킹**: DB 응답을 기다리는 동안 스레드를 반납
- WebFlux + Coroutines와 완벽하게 호환

---

## 핵심 개념

### 1. R2DBC (Reactive Relational Database Connectivity)

JDBC의 리액티브 버전입니다. 같은 SQL을 사용하지만 실행 방식이 다릅니다.

```kotlin
// JDBC (블로킹) - Spring MVC에서 사용
val order = jdbcTemplate.queryForObject(
    "SELECT * FROM orders WHERE id = ?", Order::class.java, orderId
)

// R2DBC (논블로킹) - Spring WebFlux에서 사용
val order = r2dbcEntityTemplate.selectOne(
    Query.query(Criteria.where("id").`is`(orderId)),
    OrderEntity::class.java
).awaitSingleOrNull()
```

### 2. Spring Data R2DBC

Spring이 R2DBC를 더 쉽게 쓸 수 있도록 감싸준 라이브러리입니다.

```kotlin
// Repository 인터페이스만 정의하면 구현체가 자동 생성됨
interface OrderRepository : ReactiveCrudRepository<OrderEntity, String> {
    // 메서드명으로 쿼리 자동 생성
    fun findByUserId(userId: String): Flux<OrderEntity>
    fun findByStatusAndCreatedAtBefore(status: String, before: Instant): Flux<OrderEntity>

    // 커스텀 쿼리
    @Query("SELECT * FROM orders WHERE sale_event_id = :saleEventId AND user_id = :userId")
    fun findBySaleEventAndUser(saleEventId: String, userId: String): Mono<OrderEntity>
}
```

### 3. 엔티티 매핑

```kotlin
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.relational.core.mapping.Column

@Table("orders")  // PostgreSQL 테이블명
data class OrderEntity(
    @Id
    val id: String,

    @Column("user_id")
    val userId: String,

    @Column("product_id")
    val productId: String,

    val quantity: Int,

    val status: String,  // PENDING, COMPLETED, CANCELLED

    @Column("created_at")
    val createdAt: Instant,
) {
    // Domain 모델로 변환
    fun toDomain() = Order(
        id = id,
        userId = userId,
        productId = productId,
        quantity = quantity,
        status = OrderStatus.valueOf(status),
    )

    companion object {
        fun fromDomain(order: Order) = OrderEntity(
            id = order.id,
            userId = order.userId,
            productId = order.productId,
            quantity = order.quantity,
            status = order.status.name,
            createdAt = Instant.now(),
        )
    }
}
```

### 4. 코루틴에서 사용하기

```kotlin
@Component
class R2dbcOrderRepositoryAdapter(
    private val orderRepository: OrderRepository,
    private val timeouts: TimeoutProperties,
) : OrderPersistencePort {

    override suspend fun save(order: Order): Order {
        return withTimeout(timeouts.dbQuery) {
            orderRepository.save(OrderEntity.fromDomain(order))
                .awaitSingle()
                .toDomain()
        }
    }

    override suspend fun findById(orderId: String): Order? {
        return withTimeout(timeouts.dbQuery) {
            orderRepository.findById(orderId)
                .awaitSingleOrNull()
                ?.toDomain()
        }
    }

    override suspend fun findByUser(userId: String): List<Order> {
        return withTimeout(timeouts.dbQuery) {
            orderRepository.findByUserId(userId)
                .asFlow()
                .map { it.toDomain() }
                .toList()
        }
    }
}
```

---

## Flyway (DB 마이그레이션)

### Flyway란?

SQL 파일로 DB 스키마 변경 이력을 관리하는 도구입니다.

```
V1__create_orders_table.sql        ← 첫 번째 마이그레이션
V2__add_payment_id_column.sql      ← 두 번째 마이그레이션
V3__create_payments_table.sql      ← 세 번째 마이그레이션
```

- 애플리케이션 시작 시 자동으로 미적용 마이그레이션을 실행
- 이미 적용된 마이그레이션은 건너뜀
- 팀원들이 같은 DB 스키마를 유지할 수 있게 해줌

### 파일 네이밍 규칙

```
V{버전}__{설명}.sql

V1__create_orders_table.sql     ✅ (밑줄 2개!)
V2__add_index_on_user_id.sql    ✅
v1__create_orders_table.sql     ❌ (소문자 v)
V1_create_orders_table.sql      ❌ (밑줄 1개)
```

### 마이그레이션 파일 예시

```sql
-- V1__create_orders_table.sql
CREATE TABLE orders (
    id          VARCHAR(36) PRIMARY KEY,
    user_id     VARCHAR(36) NOT NULL,
    product_id  VARCHAR(36) NOT NULL,
    quantity    INT NOT NULL DEFAULT 1,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_status ON orders(status);

-- V2__create_payments_table.sql
CREATE TABLE payments (
    id              VARCHAR(36) PRIMARY KEY,
    order_id        VARCHAR(36) NOT NULL REFERENCES orders(id),
    amount          DECIMAL(10, 2) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(64) UNIQUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_order_id ON payments(order_id);
```

### R2DBC 환경에서의 Flyway

R2DBC는 리액티브이지만, Flyway는 JDBC를 사용합니다.
그래서 이 프로젝트에서는 **JDBC 드라이버도 함께 포함**합니다.

```kotlin
// order-service/build.gradle.kts
dependencies {
    // R2DBC: 비동기 DB 연결 (런타임)
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    runtimeOnly(libs.r2dbc.postgresql)

    // Flyway: 마이그레이션 (JDBC 필요)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql.jdbc)  // Flyway를 위한 JDBC 드라이버
}
```

---

## PostgreSQL 설정

### application.yml 예시

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/flashsale
    username: flashsale
    password: flashsale123

  flyway:
    url: jdbc:postgresql://localhost:5432/flashsale  # JDBC URL (Flyway용)
    user: flashsale
    password: flashsale123
    locations: classpath:db/migration
```

### HA 구성 (읽기 전용 Replica)

```
쓰기 요청 → PostgreSQL Primary (port 5432)
읽기 요청 → PostgreSQL Replica (port 5433)  ← 스트리밍 복제
```

---

## JDBC vs R2DBC 비교

| | JDBC | R2DBC |
|---|---|---|
| 실행 방식 | 블로킹 (동기) | 논블로킹 (비동기) |
| 반환 타입 | `List<T>`, `T?` | `Flux<T>`, `Mono<T>` |
| Spring 통합 | Spring Data JPA | Spring Data R2DBC |
| ORM | Hibernate (JPA) | 없음 (직접 매핑) |
| 트랜잭션 | `@Transactional` | `@Transactional` (리액티브) |
| 호환 프레임워크 | Spring MVC | Spring WebFlux |

### R2DBC의 제한사항

- **Lazy Loading 없음**: JPA의 `@OneToMany(fetch = LAZY)` 같은 것이 없음
- **복잡한 관계 매핑 없음**: 조인은 직접 쿼리로 작성
- **Hibernate 불가**: JPA를 사용할 수 없음

→ 이 프로젝트에서는 **마이크로서비스 아키텍처**이므로 각 서비스의 DB 구조가 단순하여 큰 문제 없음

---

## 더 알아보기

- **R2DBC 공식**: [r2dbc.io](https://r2dbc.io/)
- **Spring Data R2DBC**: [Spring 공식 문서](https://docs.spring.io/spring-data/r2dbc/reference/)
- **Flyway**: [flywaydb.org](https://flywaydb.org/)
- **이 프로젝트 의존성**: `order-service`와 `payment-service`의 `build.gradle.kts` 참고
