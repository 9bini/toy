# PostgreSQL

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [핵심 개념](#3-핵심-개념)
4. [SQL 기본](#4-sql-기본)
5. [인덱스](#5-인덱스)
6. [트랜잭션과 ACID](#6-트랜잭션과-acid)
7. [이 프로젝트에서의 활용](#7-이-프로젝트에서의-활용)
8. [HA (고가용성)](#8-ha-고가용성)
9. [자주 하는 실수 / 주의사항](#9-자주-하는-실수--주의사항)
10. [정리 / 한눈에 보기](#10-정리--한눈에-보기)
11. [더 알아보기](#11-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

데이터를 **테이블(행과 열)**로 구조화하여 디스크에 안전하게 저장하는 **관계형 데이터베이스(RDBMS)**.

### 비유: 엑셀 스프레드시트

PostgreSQL의 테이블은 엑셀의 시트와 비슷하다.

```
엑셀 시트 "주문":
┌──────────┬──────────┬───────────┬──────┬──────────┐
│ 주문 ID   │ 사용자 ID │  상품 ID   │ 수량  │  상태    │
├──────────┼──────────┼───────────┼──────┼──────────┤
│ order-1  │ user-A   │ prod-100  │  1   │ PENDING  │
│ order-2  │ user-B   │ prod-200  │  2   │ COMPLETED│
└──────────┴──────────┴───────────┴──────┴──────────┘
```

차이점: 엑셀은 파일이고, PostgreSQL은 **서버**로 돌아가면서 동시에 여러 사용자가 안전하게 접근할 수 있다.

### RDBMS란?

**Relational Database Management System** (관계형 데이터베이스 관리 시스템)

- **관계형**: 데이터를 테이블(행 + 열)로 저장, 테이블 간 관계(FK)를 정의
- **관리 시스템**: 데이터 저장, 조회, 수정, 삭제를 관리하는 소프트웨어

### PostgreSQL vs MySQL

| | PostgreSQL | MySQL |
|---|---|---|
| ACID 준수 | 완벽 | 기본 지원 (InnoDB) |
| JSON 지원 | 강력 (JSONB) | 기본 |
| 확장성 | 커스텀 타입, 함수 | 제한적 |
| 동시성 | MVCC (성능 좋음) | Lock 기반 |
| 이 프로젝트 | ✅ 사용 | |

---

## 2. 왜 필요한가?

### Redis와의 역할 분담

```
Redis: 빠르지만 날아갈 수 있는 데이터 (임시)
  → 재고 수량, 대기열, 분산 락, Rate Limit

PostgreSQL: 느리지만 절대 안 날아가는 데이터 (영구)
  → 주문, 결제, 사용자 정보
```

| | Redis | PostgreSQL |
|---|---|---|
| 저장 위치 | 메모리 (RAM) | 디스크 (SSD/HDD) |
| 속도 | ~0.5ms | ~10ms |
| 영속성 | 재시작 시 소실 가능 | **안전** (WAL) |
| 트랜잭션 | 제한적 | **ACID 완전 지원** |
| 용도 | 캐시, 임시 데이터 | **영구 데이터** |

### 이 프로젝트에서 PostgreSQL이 저장하는 것

- **주문**: 주문 ID, 사용자, 상품, 수량, 상태, 생성 시각
- **결제**: 결제 ID, 주문 ID, 금액, 상태, 멱등성 키
- **상태 이력**: PENDING → COMPLETED / CANCELLED 변화 추적

---

## 3. 핵심 개념

### 3.1 Database (데이터베이스)

PostgreSQL 서버 안에 여러 데이터베이스를 만들 수 있다.

```
PostgreSQL 서버 (:5432)
├── flashsale    ← 이 프로젝트
├── postgres     ← 기본 DB
└── template1    ← 템플릿 DB
```

### 3.2 Table (테이블)

데이터를 행(Row)과 열(Column)로 저장하는 구조.

```sql
-- 테이블 생성
CREATE TABLE orders (
    id          VARCHAR(36) PRIMARY KEY,    -- 기본 키 (유일한 식별자)
    user_id     VARCHAR(36) NOT NULL,       -- NULL 허용 안 함
    product_id  VARCHAR(36) NOT NULL,
    quantity    INT NOT NULL DEFAULT 1,     -- 기본값: 1
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
```

### 3.3 Primary Key (기본 키)

각 행을 **유일하게 식별**하는 열. 중복 불가, NULL 불가.

```
orders 테이블:
  id (PK)      user_id      status
  ──────────   ──────────   ──────────
  order-001    user-A       PENDING      ← id가 유일
  order-002    user-B       COMPLETED
  order-001    user-C       PENDING      ← ❌ 에러! id 중복
```

### 3.4 Foreign Key (외래 키)

다른 테이블의 행을 **참조**하는 열. 데이터 무결성을 보장한다.

```sql
CREATE TABLE payments (
    id       VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) REFERENCES orders(id),  -- orders 테이블 참조
    amount   BIGINT NOT NULL
);
-- order_id에 orders 테이블에 없는 값을 넣으면 → 에러
```

### 3.5 WAL (Write-Ahead Log)

모든 변경사항을 **먼저 로그에 기록**하고, 그 다음에 실제 데이터를 변경한다.

```
1. 변경 요청: UPDATE orders SET status = 'COMPLETED' WHERE id = 'order-1'
2. WAL에 기록: "order-1의 status를 COMPLETED로 변경"  ← 디스크에 즉시 기록
3. 메모리에서 변경: status = 'COMPLETED'
4. 나중에 디스크에 반영 (checkpoint)

→ 3단계에서 서버가 죽어도, 2단계의 WAL로 복구 가능!
```

---

## 4. SQL 기본

### 4.1 INSERT (삽입)

```sql
INSERT INTO orders (id, user_id, product_id, quantity, status)
VALUES ('order-001', 'user-A', 'prod-100', 1, 'PENDING');
```

### 4.2 SELECT (조회)

```sql
-- 전체 조회
SELECT * FROM orders;

-- 조건 조회
SELECT * FROM orders WHERE status = 'PENDING';

-- 특정 열만
SELECT id, status FROM orders WHERE user_id = 'user-A';

-- 정렬
SELECT * FROM orders ORDER BY created_at DESC;

-- 개수 제한
SELECT * FROM orders LIMIT 10;

-- 집계
SELECT status, COUNT(*) FROM orders GROUP BY status;
-- → PENDING: 5, COMPLETED: 3
```

### 4.3 UPDATE (수정)

```sql
UPDATE orders
SET status = 'COMPLETED', updated_at = NOW()
WHERE id = 'order-001';

-- ⚠️ WHERE 없이 UPDATE하면 전체 행이 수정됨!
```

### 4.4 DELETE (삭제)

```sql
DELETE FROM orders WHERE id = 'order-001';

-- ⚠️ WHERE 없이 DELETE하면 전체 데이터 삭제!
```

---

## 5. 인덱스

### 인덱스란?

책의 **색인(찾아보기)**과 같다. 없으면 처음부터 끝까지 읽어야 하고(Full Scan),
있으면 바로 해당 페이지로 이동한다.

```
인덱스 없이 (Full Table Scan):
  100만 행 → 하나씩 비교 → 느림 (수백 ms)

인덱스 있으면 (Index Scan):
  100만 행 → 인덱스로 바로 찾기 → 빠름 (수 ms)
```

### 인덱스 생성

```sql
-- user_id로 자주 조회한다면
CREATE INDEX idx_orders_user_id ON orders(user_id);

-- 복합 인덱스 (여러 열)
CREATE INDEX idx_orders_sale_user ON orders(sale_event_id, user_id);
```

### 인덱스 트레이드오프

| | 장점 | 단점 |
|---|---|---|
| **조회** | 빠름 (수 ms) | |
| **삽입/수정** | | 느림 (인덱스도 갱신해야 함) |
| **저장 공간** | | 추가 디스크 사용 |

→ 자주 조회하는 열에만 인덱스 생성, 무분별한 인덱스는 쓰기 성능 저하

### EXPLAIN (실행 계획 확인)

쿼리가 인덱스를 사용하는지 확인한다.

```sql
EXPLAIN ANALYZE SELECT * FROM orders WHERE user_id = 'user-A';

-- 좋은 결과: "Index Scan using idx_orders_user_id"
-- 나쁜 결과: "Seq Scan on orders" (Full Scan, 인덱스 미사용)
```

---

## 6. 트랜잭션과 ACID

### 트랜잭션이란?

여러 SQL 문을 **하나의 작업 단위**로 묶는 것. 전부 성공하거나 전부 실패한다.

```sql
BEGIN;                                                -- 트랜잭션 시작
  UPDATE orders SET status = 'COMPLETED' WHERE id = 'order-1';
  UPDATE payments SET status = 'PAID' WHERE order_id = 'order-1';
COMMIT;                                               -- 전부 반영

-- 중간에 에러 발생 시
ROLLBACK;                                              -- 전부 취소
```

### ACID

| 속성 | 의미 | 예시 |
|------|------|------|
| **A**tomicity (원자성) | 전부 성공 또는 전부 실패 | 주문 + 결제가 하나의 단위 |
| **C**onsistency (일관성) | 데이터 무결성 유지 | 존재하지 않는 order_id 참조 불가 |
| **I**solation (격리성) | 트랜잭션 간 간섭 없음 | A가 수정 중인 데이터를 B가 못 봄 |
| **D**urability (지속성) | 커밋되면 영구 보존 | 서버 다운 후에도 데이터 유지 (WAL) |

---

## 7. 이 프로젝트에서의 활용

### 서비스별 테이블 분리

```
주문 서비스 (order-service):
  └── orders 테이블

결제 서비스 (payment-service):
  └── payments 테이블
```

> 마이크로서비스에서는 각 서비스가 **자기만의 테이블**을 가진다.
> 다른 서비스의 테이블을 직접 조회하지 않는다. (Kafka 이벤트로 통신)

### Docker 설정

```yaml
postgres:
  image: postgres:16-alpine
  ports: ["5432:5432"]
  environment:
    POSTGRES_DB: flashsale
    POSTGRES_USER: flashsale
    POSTGRES_PASSWORD: flashsale123
  volumes: [postgres-data:/var/lib/postgresql/data]
```

### 접속 방법

```bash
# Docker 컨테이너 내부 psql
docker exec -it flashsale-postgres psql -U flashsale

# 호스트에서 직접 접속 (psql 설치 필요)
psql -h localhost -p 5432 -U flashsale -d flashsale
```

### 유용한 psql 명령어

```
\l          데이터베이스 목록
\dt         테이블 목록
\d orders   orders 테이블 구조
\q          종료
```

---

## 8. HA (고가용성)

### 개발 환경: 단일 인스턴스

```
PostgreSQL 1대 (:5432) ← 읽기 + 쓰기
```

### 운영 환경: 스트리밍 복제

```
Primary (:5432)  ──WAL 스트리밍──►  Replica (:5433)
  쓰기 + 읽기                         읽기 전용
```

- **Primary**: 모든 쓰기 처리, WAL을 Replica에 전송
- **Replica**: Primary의 데이터를 실시간 복제, 읽기 요청 분산
- Primary 장애 시 Replica를 Primary로 승격

---

## 9. 자주 하는 실수 / 주의사항

### WHERE 없는 UPDATE/DELETE

```sql
-- ❌ 전체 데이터가 수정/삭제됨!
UPDATE orders SET status = 'CANCELLED';
DELETE FROM orders;

-- ✅ 항상 WHERE 조건 추가
UPDATE orders SET status = 'CANCELLED' WHERE id = 'order-001';
```

### 인덱스 없이 대량 조회

```sql
-- ❌ 100만 행 Full Scan
SELECT * FROM orders WHERE user_id = 'user-A';

-- ✅ 인덱스 먼저 생성
CREATE INDEX idx_orders_user_id ON orders(user_id);
SELECT * FROM orders WHERE user_id = 'user-A';  -- Index Scan
```

### N+1 문제

```
-- ❌ 주문 100건 조회 후 각각 결제 조회 = 101번 쿼리
SELECT * FROM orders;                         -- 1번
SELECT * FROM payments WHERE order_id = ?;    -- ×100번

-- ✅ JOIN으로 한 번에 (R2DBC에서는 @Query로 직접 작성)
SELECT o.*, p.amount FROM orders o
LEFT JOIN payments p ON o.id = p.order_id;    -- 1번
```

---

## 10. 정리 / 한눈에 보기

### 핵심 요약

| 개념 | 설명 |
|------|------|
| 테이블 | 행(데이터) + 열(속성)로 구성된 구조 |
| Primary Key | 행을 유일하게 식별 |
| 인덱스 | 조회 속도 향상 (책의 색인) |
| 트랜잭션 | 여러 SQL을 하나의 단위로 |
| ACID | 원자성, 일관성, 격리성, 지속성 |
| WAL | 변경사항을 미리 로그에 기록 (복구용) |

### SQL 치트시트

| 작업 | SQL |
|------|-----|
| 조회 | `SELECT * FROM 테이블 WHERE 조건` |
| 삽입 | `INSERT INTO 테이블 (열) VALUES (값)` |
| 수정 | `UPDATE 테이블 SET 열=값 WHERE 조건` |
| 삭제 | `DELETE FROM 테이블 WHERE 조건` |
| 테이블 생성 | `CREATE TABLE 이름 (열 타입 제약)` |
| 인덱스 생성 | `CREATE INDEX 이름 ON 테이블(열)` |

---

## 11. 더 알아보기

- [PostgreSQL 공식 문서](https://www.postgresql.org/docs/)
- [PostgreSQL Tutorial](https://www.postgresqltutorial.com/)
