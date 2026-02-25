# 4. Redis 활용

> **한 줄 요약**: 인메모리 데이터 저장소로, 이 프로젝트에서 캐시/대기열/분산 락/Rate Limiting 4가지 역할을 담당

---

## Redis란?

- **In-Memory**: 데이터를 RAM에 저장 → 읽기/쓰기 1ms 미만
- **Key-Value Store**: 단순한 키-값 구조이지만 다양한 자료구조 지원
- **Single-Threaded**: 명령어를 순서대로 하나씩 처리 → 자연스럽게 원자성 보장

### 왜 DB가 아닌 Redis를 쓰는가?

| | PostgreSQL | Redis |
|---|---|---|
| 저장 위치 | 디스크 | 메모리 (RAM) |
| 읽기 속도 | ~10ms | ~0.5ms |
| 동시 처리 | 수천 qps | 수십만 qps |
| 데이터 영속성 | 안전 | 재시작 시 소실 가능 |

10만 동시 접속에서 재고를 DB로 관리하면 → DB가 병목이 됨.
Redis로 관리하면 → 초당 수십만 건 처리 가능.

---

## 이 프로젝트에서의 4가지 활용

### 1. 재고 관리 + Lua Script (원자적 연산)

**문제**: 두 사용자가 동시에 마지막 1개 재고를 구매하면?

```
사용자 A: 재고 조회 → 1개 → 구매 가능!
사용자 B: 재고 조회 → 1개 → 구매 가능!  (동시에)
사용자 A: 재고 차감 → 0개
사용자 B: 재고 차감 → -1개  ← 과다 판매 발생!
```

**해결**: Redis Lua Script로 "조회 + 비교 + 차감"을 한 번에 (원자적으로) 실행

```lua
-- 재고 차감 Lua Script
-- KEYS[1] = "stock:remaining:product-123"
-- ARGV[1] = 차감할 수량

local remaining = tonumber(redis.call('GET', KEYS[1]) or '0')

if remaining >= tonumber(ARGV[1]) then
    -- 재고 충분: 차감하고 성공 반환
    redis.call('DECRBY', KEYS[1], ARGV[1])
    return remaining - tonumber(ARGV[1])  -- 남은 재고
else
    -- 재고 부족: 차감하지 않고 -1 반환
    return -1
end
```

**왜 Lua Script인가?**
- Redis는 Lua Script를 **한 덩어리로 실행** (중간에 다른 명령이 끼어들 수 없음)
- "조회 → 비교 → 차감"이 원자적으로 처리됨
- 별도의 락 없이도 동시성 문제 해결

```kotlin
// Kotlin에서 Lua Script 실행
@Component
class RedisStockAdapter(
    private val redisTemplate: ReactiveStringRedisTemplate,
) : StockPort {
    private val decrementScript = RedisScript.of<Long>("""
        local remaining = tonumber(redis.call('GET', KEYS[1]) or '0')
        if remaining >= tonumber(ARGV[1]) then
            redis.call('DECRBY', KEYS[1], ARGV[1])
            return remaining - tonumber(ARGV[1])
        else
            return -1
        end
    """.trimIndent())

    override suspend fun decrement(productId: String, quantity: Int): Boolean {
        val result = redisTemplate.execute(
            decrementScript,
            listOf(RedisKeys.Stock.remaining(productId)),
            quantity.toString()
        ).awaitSingle()

        return result >= 0  // -1이면 재고 부족
    }
}
```

### 2. 대기열 (Sorted Set)

**상황**: 10만 명이 동시에 접속 → 순서대로 처리해야 함

Redis의 **Sorted Set**은 점수(score)로 자동 정렬되는 자료구조입니다.

```
# Sorted Set: 점수 = 진입 시각 (밀리초)
queue:waiting:sale-event-1
├── user-A (score: 1708900000001)  ← 1번째
├── user-B (score: 1708900000002)  ← 2번째
├── user-C (score: 1708900000005)  ← 3번째
└── user-D (score: 1708900000010)  ← 4번째
```

```kotlin
// 대기열 진입
suspend fun enqueue(saleEventId: String, userId: String): Long {
    val key = RedisKeys.Queue.waiting(saleEventId)
    val score = System.currentTimeMillis().toDouble()

    // ZADD: Sorted Set에 추가 (이미 있으면 무시)
    redisTemplate.opsForZSet()
        .addIfAbsent(key, userId, score)
        .awaitSingle()

    // ZRANK: 현재 순위 조회 (0-based)
    return redisTemplate.opsForZSet()
        .rank(key, userId)
        .awaitSingleOrNull() ?: -1
}

// 내 순번 조회
suspend fun getPosition(saleEventId: String, userId: String): Long {
    return redisTemplate.opsForZSet()
        .rank(RedisKeys.Queue.waiting(saleEventId), userId)
        .awaitSingleOrNull()
        ?.plus(1)  // 0-based → 1-based
        ?: -1
}
```

### 3. 분산 락 (Redisson)

**상황**: 여러 서버 인스턴스가 동시에 같은 주문을 처리하면?

분산 락은 **여러 서버에서 공유 자원에 대한 동시 접근을 제어**하는 메커니즘입니다.

```kotlin
// Redisson: Redis 기반 분산 락 라이브러리
@Component
class RedissonLockAdapter(
    private val redissonClient: RedissonClient,
    private val timeouts: TimeoutProperties,
) {
    suspend fun <T> withLock(
        lockKey: String,
        action: suspend () -> T,
    ): T {
        val lock = redissonClient.getLock(lockKey)

        // tryLock(대기시간, 유지시간, 시간단위)
        val acquired = lock.tryLockAsync(
            timeouts.distributedLockWaitMs,  // 최대 3초 대기
            timeouts.distributedLockLeaseMs, // 5초 후 자동 해제
            TimeUnit.MILLISECONDS,
        ).awaitSingle()

        if (!acquired) {
            throw LockAcquisitionException("락 획득 실패: $lockKey")
        }

        return try {
            action()
        } finally {
            if (lock.isHeldByCurrentThread) {
                lock.unlockAsync().awaitSingle()
            }
        }
    }
}
```

**사용 예시**:
```kotlin
// 주문 처리 시 분산 락으로 동시 접근 방지
suspend fun processOrder(orderId: String) {
    lockAdapter.withLock("order:lock:$orderId") {
        // 이 블록은 한 번에 하나의 서버만 실행 가능
        val order = orderPort.findById(orderId)
        // ... 주문 처리 ...
    }
}
```

### 4. Rate Limiting (Token Bucket)

**상황**: 봇/매크로가 초당 수백 건의 요청을 보내면?

Token Bucket 알고리즘:
- 버킷에 일정 속도로 토큰이 채워짐
- 요청할 때마다 토큰 1개 소비
- 토큰이 없으면 요청 거부

```
버킷 (최대 50개 토큰, 초당 10개 충전)
├── 요청 1: 토큰 50 → 49 ✅
├── 요청 2: 토큰 49 → 48 ✅
├── ...
├── 요청 50: 토큰 1 → 0 ✅
├── 요청 51: 토큰 0 → ❌ 거부 (429 Too Many Requests)
└── (1초 후 토큰 10개 충전)
```

---

## Redis 키 관리 패턴

이 프로젝트에서는 모든 Redis 키를 `RedisKeys` 오브젝트에서 중앙 관리합니다.

```kotlin
// common/infrastructure/src/.../redis/RedisKeys.kt
object RedisKeys {
    object Stock {
        fun remaining(productId: String) = "stock:remaining:$productId"
        fun lock(productId: String) = "stock:lock:$productId"
    }
    object Queue {
        fun waiting(saleEventId: String) = "queue:waiting:$saleEventId"
        fun token(saleEventId: String, userId: String) = "queue:token:$saleEventId:$userId"
    }
    object RateLimit {
        fun bucket(clientId: String) = "ratelimit:bucket:$clientId"
    }
    object Order {
        fun idempotencyKey(key: String) = "order:idempotency:$key"
    }
}
```

**키 네이밍 규칙**: `{도메인}:{엔티티}:{id}`

---

## Redis 자료구조 요약

| 자료구조 | 용도 | 이 프로젝트에서의 사용 |
|---------|------|---------------------|
| **String** | 단일 값 저장 | 재고 수량, 멱등성 키 |
| **Hash** | 필드-값 쌍 | 주문 상태, 사용자 세션 |
| **Sorted Set** | 점수로 정렬된 집합 | 대기열 (진입 시각 순 정렬) |
| **List** | 순서가 있는 목록 | - |
| **Set** | 중복 없는 집합 | - |

---

## Redis HA (고가용성)

개발 환경에서는 Redis 단일 인스턴스를 사용하지만,
운영 환경에서는 `docker-compose.ha.yml`로 **Sentinel** 구성을 사용합니다.

```
┌─────────────┐     ┌──────────────┐
│ Redis Primary│────▶│ Redis Replica│
└──────┬──────┘     └──────────────┘
       │ 모니터링
┌──────▼──────┐
│  Sentinel 1  │
│  Sentinel 2  │  ← Primary 장애 시 자동으로 Replica를 새 Primary로 승격
│  Sentinel 3  │
└─────────────┘
```

---

## 주의사항

1. **Redis는 영속 저장소가 아님**: 서버 재시작 시 데이터 소실 가능 → 중요 데이터는 PostgreSQL에 저장
2. **메모리 제한**: `maxmemory 256mb` (개발 환경) → 초과 시 `allkeys-lru` 정책으로 오래된 키 삭제
3. **Lua Script 실행 시간**: 너무 오래 걸리는 스크립트는 다른 모든 명령을 블로킹 → 타임아웃 200ms 설정

---

## 더 알아보기

- **Redis 공식 문서**: [redis.io/docs](https://redis.io/docs/)
- **Redisson (분산 락)**: [github.com/redisson/redisson](https://github.com/redisson/redisson)
- **이 프로젝트 관련 파일**: `common/infrastructure/src/.../redis/RedisKeys.kt`
