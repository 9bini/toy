# Redis

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [핵심 개념](#3-핵심-개념)
4. [자료구조와 명령어](#4-자료구조와-명령어)
5. [Lua Script](#5-lua-script)
6. [영속성 (Persistence)](#6-영속성-persistence)
7. [이 프로젝트에서의 활용](#7-이-프로젝트에서의-활용)
8. [HA (고가용성)](#8-ha-고가용성)
9. [자주 하는 실수 / 주의사항](#9-자주-하는-실수--주의사항)
10. [정리 / 한눈에 보기](#10-정리--한눈에-보기)
11. [더 알아보기](#11-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

**메모리(RAM)에 데이터를 저장하는 초고속 Key-Value 저장소**. 읽기/쓰기가 1ms 미만.

### 비유: 포스트잇 vs 서류 캐비넷

- **서류 캐비넷 (PostgreSQL)**: 안전하게 보관, 꺼내려면 시간이 걸림 (디스크 I/O)
- **포스트잇 (Redis)**: 모니터에 붙여놓고 바로 확인, 대신 컴퓨터 꺼지면 사라질 수 있음 (메모리)

### 특징

| 특징 | 설명 |
|------|------|
| **In-Memory** | RAM에 저장 → 디스크 대비 100배 빠름 |
| **Single-Threaded** | 명령을 한 줄로 순서대로 처리 → 동시성 문제 없음 |
| **다양한 자료구조** | String, Hash, List, Set, Sorted Set 등 |
| **TTL** | 키에 만료 시간 설정 가능 (자동 삭제) |
| **Pub/Sub** | 메시지 발행/구독 (실시간 알림) |

---

## 2. 왜 필요한가?

### Before: DB만 사용

```
사용자 1만명 동시 접속:
  각 요청 → PostgreSQL 조회 (10ms) → 응답

  DB 부하: 1만 qps × 10ms = DB가 버틸 수 없음
  → 응답 지연 → 타임아웃 → 서비스 다운
```

### After: Redis 캐시 추가

```
사용자 1만명 동시 접속:
  각 요청 → Redis 조회 (0.5ms) → 응답     (99% 히트)
        → Redis에 없으면 → PostgreSQL 조회 → Redis에 저장

  Redis 부하: 1만 qps × 0.5ms = 여유
  DB 부하: 100 qps (캐시 미스만) = 여유
```

### 이 프로젝트에서 Redis를 사용하는 4가지 이유

| 역할 | 왜 Redis인가 | DB로는 안 되는 이유 |
|------|-------------|-----------------|
| **재고 관리** | 0.5ms에 차감 | 10ms면 동시 주문 시 재고 꼬임 |
| **대기열** | Sorted Set으로 순위 관리 | DB ORDER BY는 느림 |
| **분산 락** | 원자적 잠금 | DB Lock은 스케일 아웃 불가 |
| **Rate Limiting** | 초당 수만 건 체크 | DB로는 병목 |

---

## 3. 핵심 개념

### 3.1 Key-Value 구조

모든 데이터는 **키(이름) - 값(데이터)** 쌍으로 저장된다.

```
┌─────────────────────────┬────────────────┐
│          Key            │     Value      │
├─────────────────────────┼────────────────┤
│ stock:remaining:prod-1  │ 1000           │
│ queue:waiting:sale-1    │ (Sorted Set)   │
│ order:idempotency:abc   │ "1"            │
└─────────────────────────┴────────────────┘
```

### 3.2 TTL (Time To Live)

키에 만료 시간을 설정하면, 시간이 지나면 **자동으로 삭제**된다.

```redis
SET mykey "hello"
EXPIRE mykey 60          # 60초 후 자동 삭제
TTL mykey                # → 58 (남은 초)

SETEX mykey 60 "hello"   # SET + EXPIRE 한 번에 (원자적)
```

### 3.3 Single-Threaded와 원자성

Redis는 명령을 **한 번에 하나씩** 처리한다. 따라서 하나의 명령은 중간에 끊기지 않는다.

```
스레드 A: INCR counter  ──────────────────►  결과: 1
스레드 B: INCR counter  ──(A 완료 후 실행)──►  결과: 2

절대 "둘 다 0을 읽어서 둘 다 1이 되는" 문제가 없음
```

하지만 **여러 명령의 조합**은 원자적이지 않다:
```
# 이건 위험!
remaining = GET stock:remaining:prod-1     # 1000
# ← 이 사이에 다른 요청이 끼어들 수 있음!
SET stock:remaining:prod-1 (remaining - 1) # 999
```
→ 이 문제를 해결하려면 **Lua Script** 또는 **Redisson 분산 락** 사용

### 3.4 메모리 정책 (Eviction Policy)

메모리가 가득 차면 어떤 키를 삭제할지 결정하는 정책이다.

| 정책 | 동작 |
|------|------|
| `noeviction` | 삭제 안 함, 쓰기 에러 반환 |
| **`allkeys-lru`** | 모든 키 중 가장 오래 사용 안 한 키 삭제 ← **이 프로젝트** |
| `volatile-lru` | TTL 설정된 키 중 LRU 삭제 |
| `allkeys-random` | 무작위 삭제 |

```
이 프로젝트 설정:
  --maxmemory 256mb                  # 최대 256MB
  --maxmemory-policy allkeys-lru     # 가장 안 쓰는 키부터 삭제
```

---

## 4. 자료구조와 명령어

### 4.1 String (문자열)

가장 기본적인 타입. 숫자, 문자열, 바이너리 모두 저장 가능.

```redis
# 기본 CRUD
SET key "value"              # 저장
GET key                      # → "value"
DEL key                      # 삭제

# 숫자 연산 (원자적!)
SET counter 100
INCR counter                 # → 101
DECRBY counter 5             # → 96
INCRBY counter 10            # → 106

# 조건부 저장 (키가 없을 때만)
SETNX key "value"            # SET if Not eXists
# → 1 (성공) 또는 0 (이미 존재)

# TTL과 함께 저장
SETEX key 3600 "value"       # 3600초(1시간) 후 자동 삭제
```

**이 프로젝트 사용처**:
- 재고 수량: `stock:remaining:{productId}` → `"1000"`
- 멱등성 키: `order:idempotency:{key}` → `"1"` (TTL 24시간)

### 4.2 Hash (해시)

하나의 키 안에 **여러 필드-값 쌍**을 저장. 객체 저장에 적합.

```redis
# 저장
HSET user:1001 name "김철수" age "30" email "kim@test.com"

# 특정 필드 조회
HGET user:1001 name           # → "김철수"

# 전체 필드 조회
HGETALL user:1001
# → "name" "김철수" "age" "30" "email" "kim@test.com"

# 필드 존재 확인
HEXISTS user:1001 name        # → 1 (있음)

# 특정 필드만 삭제
HDEL user:1001 email

# 숫자 필드 증감
HINCRBY user:1001 age 1       # → 31
```

**이 프로젝트 사용처**:
- 주문 상태: `order:user:{userId}:sale:{saleId}` → `{status: "PENDING", orderId: "..."}`

### 4.3 Sorted Set (정렬 집합)

각 멤버에 **점수(score)**가 있고, 점수 순으로 **자동 정렬**된다.
대기열, 순위표에 최적.

```redis
# 추가 (점수 = 타임스탬프)
ZADD queue:waiting:sale-1 1708900001 "user-A"
ZADD queue:waiting:sale-1 1708900002 "user-B"
ZADD queue:waiting:sale-1 1708900000 "user-C"    # 가장 일찍 → 1등

# 순위 조회 (0-based, 점수 낮은 순)
ZRANK queue:waiting:sale-1 "user-C"     # → 0 (1등)
ZRANK queue:waiting:sale-1 "user-A"     # → 1 (2등)
ZRANK queue:waiting:sale-1 "user-B"     # → 2 (3등)

# 상위 N명 조회
ZRANGE queue:waiting:sale-1 0 9         # 상위 10명 (인덱스 0~9)

# 전체 인원 수
ZCARD queue:waiting:sale-1              # → 3

# 점수 범위로 조회
ZRANGEBYSCORE queue:waiting:sale-1 0 1708900001
# → "user-C", "user-A"

# 멤버 제거
ZREM queue:waiting:sale-1 "user-A"

# 점수 조회
ZSCORE queue:waiting:sale-1 "user-C"    # → "1708900000"
```

**이 프로젝트 사용처**:
- 대기열: 점수 = 진입 시각, 멤버 = 사용자 ID → 선착순 자동 정렬

### 4.4 List (리스트)

순서가 있는 문자열 목록. 스택(LIFO)이나 큐(FIFO)로 사용.

```redis
LPUSH mylist "a" "b" "c"    # 왼쪽에 추가: ["c", "b", "a"]
RPUSH mylist "d"             # 오른쪽에 추가: ["c", "b", "a", "d"]
LPOP mylist                  # 왼쪽에서 꺼냄: "c"
RPOP mylist                  # 오른쪽에서 꺼냄: "d"
LRANGE mylist 0 -1           # 전체 조회: ["b", "a"]
LLEN mylist                  # 길이: 2
```

### 4.5 Set (집합)

중복 없는 문자열 모음. 멤버십 확인이 O(1).

```redis
SADD myset "a" "b" "c"
SISMEMBER myset "a"          # → 1 (포함)
SISMEMBER myset "d"          # → 0 (미포함)
SMEMBERS myset               # → {"a", "b", "c"}
SCARD myset                  # → 3 (개수)
SREM myset "a"               # 삭제
```

---

## 5. Lua Script

### 왜 필요한가?

Redis 명령은 각각 원자적이지만, **여러 명령의 조합**은 원자적이지 않다.

```
# 위험한 코드 (의사코드):
remaining = redis.GET("stock:remaining:prod-1")    # 1000
if remaining >= 1:
    # ← 이 시점에 다른 요청이 remaining=1000을 읽고 차감할 수 있음!
    redis.DECRBY("stock:remaining:prod-1", 1)      # 999 (999가 아닌 998이어야 하는데!)
```

Lua Script는 **전체 스크립트를 원자적으로 실행**한다. 중간에 다른 요청이 끼어들 수 없다.

### 기본 문법

```lua
-- 변수
local remaining = 100
local name = "hello"

-- 조건문
if remaining >= 1 then
    remaining = remaining - 1
end

-- Redis 명령 호출
redis.call('SET', 'mykey', 'myvalue')
local val = redis.call('GET', 'mykey')

-- 반환
return remaining
```

### Redis에서 실행

```redis
-- EVAL "스크립트" 키개수 키1 키2 ... 인자1 인자2 ...
EVAL "return redis.call('GET', KEYS[1])" 1 mykey
--                                        ↑ 키 1개  ↑ 키 목록
```

- `KEYS[1]`, `KEYS[2]`, ... → 키 이름들
- `ARGV[1]`, `ARGV[2]`, ... → 인자 값들

### 이 프로젝트의 재고 차감 Lua Script

```lua
-- KEYS[1] = stock:remaining:{productId}
-- ARGV[1] = 차감할 수량

local remaining = tonumber(redis.call('GET', KEYS[1]) or '0')

if remaining >= tonumber(ARGV[1]) then
    -- 재고 충분: 차감하고 남은 수량 반환
    redis.call('DECRBY', KEYS[1], ARGV[1])
    return remaining - tonumber(ARGV[1])
else
    -- 재고 부족: -1 반환
    return -1
end
```

**왜 안전한가?**
1. GET과 DECRBY 사이에 다른 요청 끼어들기 불가능
2. 전체 스크립트가 원자적으로 실행
3. 실패 시 아무것도 변경되지 않음

---

## 6. 영속성 (Persistence)

Redis는 메모리에 데이터를 저장하므로 재시작 시 데이터가 사라진다.
이를 방지하기 위한 2가지 방법이 있다.

### 6.1 RDB (스냅샷)

일정 간격으로 메모리 전체를 디스크에 **스냅샷**으로 저장한다.

```
장점: 복구 속도 빠름, 파일 크기 작음
단점: 마지막 스냅샷 이후 데이터 유실 가능
```

### 6.2 AOF (Append Only File)

모든 쓰기 명령을 **로그 파일에 기록**한다. 재시작 시 로그를 재실행하여 복구.

```
장점: 데이터 유실 최소화 (최대 1초분)
단점: 파일 크기 큼, 복구 속도 느림
```

### 이 프로젝트 설정

```
--appendonly yes    ← AOF 사용 (데이터 안전 우선)
```

---

## 7. 이 프로젝트에서의 활용

### 키 패턴 전체 정리

| 키 패턴 | 자료구조 | 용도 |
|--------|---------|------|
| `stock:remaining:{productId}` | String | 실시간 재고 수량 |
| `stock:initial:{productId}` | String | 초기 재고 수량 |
| `queue:waiting:{saleEventId}` | Sorted Set | 대기열 (점수=진입시각) |
| `queue:entered:{saleEventId}` | Set | 입장 완료 사용자 |
| `order:idempotency:{key}` | String+TTL | 중복 주문 방지 |
| `ratelimit:bucket:{clientId}` | Hash | Token Bucket 상태 |
| `stock:lock:{productId}` | String (Redisson) | 분산 락 |

### Docker 설정

```yaml
redis:
  image: redis:7.4-alpine
  ports: ["6379:6379"]
  volumes: [redis-data:/data]
  command: redis-server --appendonly yes --maxmemory 256mb --maxmemory-policy allkeys-lru
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
```

### 프로젝트 파일

- `common/infrastructure/src/.../redis/RedisKeys.kt` — 키 패턴 중앙 관리
- `common/infrastructure/src/.../redis/` — Redis 관련 어댑터

---

## 8. HA (고가용성)

### 개발 환경: 단일 인스턴스

```
Redis 1대 (:6379)
└── 읽기 + 쓰기
```

### 운영 환경: Sentinel 구성

```
┌──────────────┐     ┌──────────────┐
│   Primary    │────►│   Replica    │  데이터 복제
│   :6379      │     │   :6380      │
└──────┬───────┘     └──────────────┘
       │ 감시                 │ 감시
┌──────┴───────────────────────┐
│     Sentinel x3              │  장애 감지 + 자동 페일오버
│  :26379, :26380, :26381      │
└──────────────────────────────┘
```

- **Replica**: Primary 데이터를 실시간 복제 (읽기 분산 가능)
- **Sentinel**: Primary 장애 시 Replica를 자동으로 Primary로 승격
- **3대인 이유**: 과반수(2/3) 합의로 오판 방지

---

## 9. 자주 하는 실수 / 주의사항

### KEYS 명령 사용 금지

```redis
# ❌ 모든 키를 스캔 → 운영 환경에서 Redis가 멈춤 (블로킹)
KEYS stock:*

# ✅ 커서 기반 점진적 스캔
SCAN 0 MATCH stock:* COUNT 100
```

### 큰 키 (Big Key) 주의

```
# ❌ 하나의 키에 100만 개 멤버
ZADD huge-sorted-set score1 member1 ... (100만 개)
# → 삭제 시 Redis가 수 초간 멈춤

# ✅ 키를 분할
queue:waiting:sale-1:shard-0
queue:waiting:sale-1:shard-1
```

### GET + SET 분리 (Race Condition)

```
# ❌ 두 명령 사이에 다른 요청이 끼어듦
val = GET key
SET key (val - 1)

# ✅ 원자적 명령 사용
DECRBY key 1

# ✅ 또는 Lua Script 사용
```

---

## 10. 정리 / 한눈에 보기

### 자료구조 선택 가이드

| 상황 | 자료구조 | 이유 |
|------|---------|------|
| 단일 숫자/문자열 | String | 가장 단순, INCR/DECR 원자적 |
| 객체 저장 | Hash | 필드별 개별 접근 가능 |
| 순위/대기열 | Sorted Set | 점수 기반 자동 정렬 |
| 중복 확인 | Set | O(1) 멤버십 확인 |
| 최근 N개 | List | LPUSH + LTRIM |

### 명령어 치트시트

| 하고 싶은 것 | 명령어 |
|-------------|--------|
| 값 저장 | `SET key value` |
| 값 조회 | `GET key` |
| 만료 설정 | `EXPIRE key seconds` |
| 원자적 증감 | `INCR key` / `DECRBY key N` |
| 정렬 집합 추가 | `ZADD key score member` |
| 정렬 집합 순위 | `ZRANK key member` |
| 해시 저장 | `HSET key field value` |

---

## 11. 더 알아보기

- [Redis 공식 문서](https://redis.io/docs/)
- [Redis 명령어 레퍼런스](https://redis.io/commands/)
- [Redis University (무료 강의)](https://university.redis.io/)
