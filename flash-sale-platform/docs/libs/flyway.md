# Flyway

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

SQL 파일로 DB 스키마 변경 이력을 **버전 관리**하는 마이그레이션 도구.

### 비유: Git, 하지만 DB용

**Git**: 코드의 변경 이력을 버전으로 관리
**Flyway**: DB 스키마(테이블 구조)의 변경 이력을 버전으로 관리

```
Git:
  commit 1: 파일 추가
  commit 2: 함수 수정
  commit 3: 클래스 삭제

Flyway:
  V1: 테이블 생성
  V2: 열 추가
  V3: 인덱스 생성
```

---

## 2. 왜 필요한가?

### Before: Flyway 없이

```
개발자 A: "orders 테이블에 payment_id 열 추가했어"
개발자 B: "나는 아직 안 했는데? 에러남"
운영 서버: "어떤 ALTER TABLE을 실행해야 하지?"
```

- 누가 어떤 스키마 변경을 했는지 추적 불가
- 팀원마다 DB 상태가 다름
- 운영 서버에 수동으로 SQL 실행 → 실수 위험

### After: Flyway 사용

```
1. V1__create_orders.sql 작성
2. Git에 커밋
3. 애플리케이션 시작 시 자동 실행
4. 모든 환경(개발, 테스트, 운영)의 DB가 동일한 상태 보장
```

---

## 3. 핵심 개념

### 3.1 마이그레이션 파일

DB 스키마를 변경하는 SQL 파일. 하나의 파일 = 하나의 버전.

```
src/main/resources/db/migration/
├── V1__create_orders_table.sql        ← 첫 번째 마이그레이션
├── V2__add_payment_id_column.sql      ← 두 번째
├── V3__create_payments_table.sql      ← 세 번째
└── V4__add_indexes.sql                ← 네 번째
```

### 3.2 파일 네이밍 규칙

```
V{버전}__{설명}.sql
↑         ↑↑    ↑
│         ││    └── 확장자
│         │└────── 설명 (밑줄로 단어 구분)
│         └─────── 밑줄 2개 (반드시!)
└──────────────── V + 버전 번호

✅ 올바른 예시:
V1__create_orders_table.sql
V2__add_payment_id.sql
V10__create_indexes.sql

❌ 잘못된 예시:
v1__create_orders.sql          ← 소문자 v
V1_create_orders.sql           ← 밑줄 1개
V1__CREATE_ORDERS.SQL          ← 대문자 확장자
create_orders.sql              ← V 없음
```

### 3.3 실행 흐름

애플리케이션 시작 시 Flyway가 자동으로 실행된다.

```
1. flyway_schema_history 테이블 확인 (이력 테이블)
2. 이미 적용된 버전 확인
3. 아직 적용 안 된 버전만 순서대로 실행
4. 실행 결과를 이력 테이블에 기록

flyway_schema_history 테이블:
┌─────────┬───────────────────────────┬───────────┬─────────┐
│ version │ description               │ checksum  │ success │
├─────────┼───────────────────────────┼───────────┼─────────┤
│ 1       │ create orders table       │ 123456789 │ true    │
│ 2       │ add payment id            │ 987654321 │ true    │
│ 3       │ create payments table     │ (미적용)   │         │ ← 이번에 실행됨
└─────────┴───────────────────────────┴───────────┴─────────┘
```

### 3.4 체크섬 (Checksum)

Flyway는 각 마이그레이션 파일의 **체크섬(해시값)**을 저장한다.
이미 적용된 파일이 변경되면 체크섬 불일치 에러가 발생한다.

```
V1__create_orders.sql 적용 완료 → 체크섬: 123456789

나중에 V1 파일을 수정하면:
→ "Migration checksum mismatch for version 1"
→ 에러! 이미 적용된 파일은 수정 불가!
```

---

## 4. 기본 사용법

### 마이그레이션 파일 작성

```sql
-- V1__create_orders_table.sql
CREATE TABLE IF NOT EXISTS orders (
    id          VARCHAR(36)     PRIMARY KEY,
    user_id     VARCHAR(36)     NOT NULL,
    product_id  VARCHAR(36)     NOT NULL,
    quantity    INT             NOT NULL DEFAULT 1,
    status      VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
```

```sql
-- V2__add_sale_event_id.sql
ALTER TABLE orders ADD COLUMN sale_event_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_orders_sale_event ON orders(sale_event_id);
```

```sql
-- V3__create_payments_table.sql
CREATE TABLE IF NOT EXISTS payments (
    id              VARCHAR(36)     PRIMARY KEY,
    order_id        VARCHAR(36)     NOT NULL,
    amount          BIGINT          NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(100)    UNIQUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
```

### 파일 위치

```
src/main/resources/db/migration/
```

Spring Boot가 이 경로를 자동으로 스캔한다.

---

## 5. 이 프로젝트에서의 활용

### R2DBC 환경의 특이점

Flyway는 **JDBC만 지원**한다. R2DBC 프로젝트에서도 마이그레이션용 JDBC 드라이버가 필요하다.

```
런타임 쿼리:  애플리케이션 → R2DBC 드라이버 → PostgreSQL
마이그레이션:  Flyway → JDBC 드라이버 → PostgreSQL

→ 두 개의 드라이버가 공존!
```

### 의존성

```kotlin
// build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
runtimeOnly(libs.r2dbc.postgresql)          // 런타임 R2DBC

implementation(libs.flyway.core)             // Flyway 코어
implementation(libs.flyway.database.postgresql)  // Flyway PostgreSQL 지원
runtimeOnly(libs.postgresql.jdbc)            // Flyway용 JDBC 드라이버
```

### 설정

```yaml
# application.yml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/flashsale       # R2DBC (런타임)
    username: flashsale
    password: flashsale123
  flyway:
    url: jdbc:postgresql://localhost:5432/flashsale         # JDBC (마이그레이션)
    user: flashsale
    password: flashsale123
    locations: classpath:db/migration
```

---

## 6. 자주 하는 실수 / 주의사항

### 이미 적용된 파일 수정

```
# ❌ V1이 이미 적용된 상태에서 내용 변경
→ "Migration checksum mismatch" 에러

# ✅ 변경이 필요하면 새 버전 파일 추가
V1__create_orders.sql   (이미 적용, 수정 금지)
V4__alter_orders.sql    (새 마이그레이션으로 변경사항 추가)
```

### 버전 번호 중복

```
# ❌ 같은 버전 번호
V3__create_payments.sql
V3__add_indexes.sql

# ✅ 고유한 버전 번호
V3__create_payments.sql
V4__add_indexes.sql
```

### 밑줄 개수 실수

```
# ❌ 밑줄 1개 (Flyway가 인식 못함)
V1_create_orders.sql

# ✅ 밑줄 2개
V1__create_orders.sql
```

### 되돌리기 (Rollback)

```
Flyway 무료 버전은 자동 롤백을 지원하지 않는다.
되돌리려면 수동으로 ALTER TABLE / DROP TABLE SQL 작성.

# 롤백용 마이그레이션 추가
V5__rollback_add_column.sql
  → ALTER TABLE orders DROP COLUMN sale_event_id;
```

---

## 7. 정리 / 한눈에 보기

### 핵심 규칙

| 규칙 | 설명 |
|------|------|
| `V{N}__{설명}.sql` | 파일 네이밍 (밑줄 2개!) |
| `src/main/resources/db/migration/` | 파일 위치 |
| 적용된 파일 수정 금지 | 체크섬 불일치 에러 |
| 변경은 새 버전으로 | `V4__alter_xxx.sql` |
| 버전 순서대로 실행 | V1 → V2 → V3 → ... |

### 이 프로젝트 설정 요약

| 항목 | 값 |
|------|-----|
| Flyway 버전 | 10.22.0 |
| R2DBC URL | `r2dbc:postgresql://localhost:5432/flashsale` |
| JDBC URL (Flyway용) | `jdbc:postgresql://localhost:5432/flashsale` |
| 마이그레이션 위치 | `classpath:db/migration` |

---

## 8. 더 알아보기

- [Flyway 공식 문서](https://documentation.red-gate.com/flyway)
- [Flyway + Spring Boot 가이드](https://docs.spring.io/spring-boot/docs/current/reference/html/howto.html#howto.data-initialization.migration-tool.flyway)
