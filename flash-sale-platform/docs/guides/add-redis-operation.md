# 새 Redis 연산 추가 가이드

> 이 프로젝트에서 사용하는 4가지 Redis 패턴을 각각 예제와 함께 설명

---

## 목차

1. [공통 절차](#1-공통-절차)
2. [패턴 1: Lua Script (원자적 연산)](#패턴-1-lua-script-원자적-연산)
3. [패턴 2: 분산 락 (Redisson)](#패턴-2-분산-락-redisson)
4. [패턴 3: Sorted Set 대기열](#패턴-3-sorted-set-대기열)
5. [패턴 4: Token Bucket Rate Limiting](#패턴-4-token-bucket-rate-limiting)
6. [테스트](#테스트)
7. [언제 어떤 패턴을 쓰는가?](#언제-어떤-패턴을-쓰는가)

---

## 1. 공통 절차

어떤 Redis 연산이든 다음 순서를 따른다:

```
1. RedisKeys.kt에 키 패턴 등록
2. 아웃바운드 Port 인터페이스 정의 (기술 세부사항 노출 금지)
3. Redis Adapter 구현 (Port 구현체)
4. withTimeout 적용 (TimeoutProperties 사용)
5. Testcontainers Redis로 통합 테스트
```

### RedisKeys에 키 등록 (항상 첫 번째)

```kotlin
// common/infrastructure/src/.../redis/RedisKeys.kt
object RedisKeys {
    // 기존 키들...

    object NewDomain {
        /** {설명} ({Redis 자료구조}) */
        fun keyName(id: String) = "newdomain:entity:$id"
    }
}
```

키 네이밍 규칙: `{도메인}:{엔티티}:{id}`

---

## 패턴 1: Lua Script (원자적 연산)

### 언제 사용?

**여러 Redis 명령을 하나의 원자적 연산으로 묶어야 할 때.**

예: "재고 조회 → 비교 → 차감"을 중간에 끊기지 않게 실행.

### 예제: 재고 차감

```kotlin
@Component
class RedisStockAdapter(
    private val redisTemplate: ReactiveStringRedisTemplate,
) : StockPort {

    // Lua Script 정의 (클래스 수준에서 한 번만)
    private val decrementScript = RedisScript.of<Long>(
        """
        local remaining = tonumber(redis.call('GET', KEYS[1]) or '0')
        if remaining >= tonumber(ARGV[1]) then
            redis.call('DECRBY', KEYS[1], ARGV[1])
            return remaining - tonumber(ARGV[1])
        else
            return -1
        end
        """.trimIndent(),
        Long::class.java,  // 반환 타입
    )

    override suspend fun decrement(productId: String, quantity: Int): Long =
        redisTemplate.execute(
            decrementScript,
            listOf(RedisKeys.Stock.remaining(productId)),   // KEYS[1]
            listOf(quantity.toString()),                     // ARGV[1]
        ).awaitSingle()
}
```

### Lua Script 작성 규칙

```lua
-- KEYS[1], KEYS[2], ... → Redis 키 (리스트로 전달)
-- ARGV[1], ARGV[2], ... → 인자 값 (리스트로 전달)

-- Redis 명령 호출
redis.call('GET', KEYS[1])
redis.call('SET', KEYS[1], ARGV[1])
redis.call('DECRBY', KEYS[1], ARGV[1])
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])

-- 반환
return 결과값
```

### Kotlin에서 실행

```kotlin
val script = RedisScript.of<Long>("...lua...", Long::class.java)

val result = redisTemplate.execute(
    script,
    listOf("key1", "key2"),     // KEYS 리스트
    listOf("arg1", "arg2"),     // ARGV 리스트
).awaitSingle()
```

---

## 패턴 2: 분산 락 (Redisson)

### 언제 사용?

**여러 서버 인스턴스가 같은 자원에 동시 접근하는 것을 방지할 때.**

예: 주문 처리 시 같은 주문을 2개 서버가 동시에 처리하면 안 됨.

### 예제: 주문 처리 분산 락

```kotlin
@Component
class DistributedLockAdapter(
    private val redissonClient: RedissonClient,
    private val timeouts: TimeoutProperties,
) {
    companion object : Log

    /**
     * 분산 락을 획득한 후 action을 실행한다.
     * 락 획득 실패 시 null을 반환한다.
     */
    suspend fun <T> withLock(
        lockKey: String,
        action: suspend () -> T,
    ): T? {
        val lock = redissonClient.getLock(lockKey)

        val acquired = lock.tryLockAsync(
            timeouts.distributedLockWaitMs,   // 최대 3초 대기
            timeouts.distributedLockLeaseMs,  // 5초 후 자동 해제
            java.util.concurrent.TimeUnit.MILLISECONDS,
        ).awaitSingle()

        if (!acquired) {
            logger.warn { "락 획득 실패: $lockKey" }
            return null
        }

        return try {
            action()
        } finally {
            // 반드시 finally에서 해제
            if (lock.isHeldByCurrentThread) {
                lock.unlockAsync().awaitSingle()
            }
        }
    }
}

// 사용 예시
suspend fun processOrder(orderId: String) {
    val result = lockAdapter.withLock("order:lock:$orderId") {
        // 이 블록은 한 번에 하나의 서버만 실행
        orderPort.findById(orderId)
        // ... 주문 처리 ...
    } ?: throw LockAcquisitionException("락 획득 실패")
}
```

### 핵심 주의사항

```kotlin
// ❌ finally 없이 unlock → 예외 시 데드락
lock.tryLockAsync(...).awaitSingle()
processOrder()
lock.unlockAsync().awaitSingle()  // 예외 발생 시 실행 안 됨!

// ✅ finally 블록에서 unlock
try {
    processOrder()
} finally {
    if (lock.isHeldByCurrentThread) {
        lock.unlockAsync().awaitSingle()
    }
}
```

---

## 패턴 3: Sorted Set 대기열

### 언제 사용?

**순서가 보장되어야 하는 대기열.** score = 진입 시각으로 선착순 보장.

### 예제: 대기열 진입 + 순번 조회

```kotlin
@Component
class RedisQueueAdapter(
    private val redisTemplate: ReactiveStringRedisTemplate,
) : QueuePort {

    /** 대기열 진입. 이미 있으면 무시. 현재 순번 반환 */
    override suspend fun enqueue(saleEventId: String, userId: String): Long {
        val key = RedisKeys.Queue.waiting(saleEventId)
        val score = System.currentTimeMillis().toDouble()

        // ZADD NX: 이미 있으면 추가하지 않음 (중복 방지)
        redisTemplate.opsForZSet()
            .addIfAbsent(key, userId, score)
            .awaitSingle()

        // ZRANK: 0-based 순위 조회
        return redisTemplate.opsForZSet()
            .rank(key, userId)
            .awaitSingleOrNull()
            ?.plus(1)  // 0-based → 1-based
            ?: -1      // 없으면 -1
    }

    /** 내 순번 조회 */
    override suspend fun getPosition(saleEventId: String, userId: String): Long =
        redisTemplate.opsForZSet()
            .rank(RedisKeys.Queue.waiting(saleEventId), userId)
            .awaitSingleOrNull()
            ?.plus(1)
            ?: -1

    /** 상위 N명 꺼내기 (대기열에서 제거) */
    override suspend fun dequeueTop(saleEventId: String, count: Long): List<String> {
        val key = RedisKeys.Queue.waiting(saleEventId)

        // ZPOPMIN: 점수가 가장 낮은(가장 먼저 진입한) N명 꺼내기
        return redisTemplate.opsForZSet()
            .popMin(key, count)
            .map { it.value!! }
            .collectList()
            .awaitSingle()
    }

    /** 전체 대기 인원 수 */
    override suspend fun getWaitingCount(saleEventId: String): Long =
        redisTemplate.opsForZSet()
            .size(RedisKeys.Queue.waiting(saleEventId))
            .awaitSingle()
}
```

### Sorted Set 주요 명령어

| 명령 | Kotlin 메서드 | 설명 |
|------|-------------|------|
| `ZADD NX` | `addIfAbsent(key, value, score)` | 없으면 추가 |
| `ZRANK` | `rank(key, value)` | 순위 조회 (0-based) |
| `ZPOPMIN` | `popMin(key, count)` | 점수 낮은 순으로 꺼내기 |
| `ZCARD` | `size(key)` | 전체 개수 |
| `ZRANGE` | `range(key, start, end)` | 범위 조회 |
| `ZREM` | `remove(key, value)` | 특정 멤버 삭제 |

---

## 패턴 4: Token Bucket Rate Limiting

### 언제 사용?

**단위 시간당 요청 수를 제한할 때.**

### 예제: IP 기반 Rate Limiting

```kotlin
@Component
class RedisRateLimitAdapter(
    private val redisTemplate: ReactiveStringRedisTemplate,
) : RateLimitPort {

    // Lua Script: 토큰 차감 + 충전을 원자적으로
    private val rateLimitScript = RedisScript.of<Long>(
        """
        local key = KEYS[1]
        local max_tokens = tonumber(ARGV[1])
        local refill_rate = tonumber(ARGV[2])
        local now = tonumber(ARGV[3])

        local data = redis.call('HMGET', key, 'tokens', 'last_refill')
        local tokens = tonumber(data[1]) or max_tokens
        local last_refill = tonumber(data[2]) or now

        -- 경과 시간에 따라 토큰 충전
        local elapsed = (now - last_refill) / 1000.0
        tokens = math.min(max_tokens, tokens + elapsed * refill_rate)

        if tokens >= 1 then
            tokens = tokens - 1
            redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
            redis.call('EXPIRE', key, 60)
            return 1  -- 허용
        else
            redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
            redis.call('EXPIRE', key, 60)
            return 0  -- 거부
        end
        """.trimIndent(),
        Long::class.java,
    )

    /** 요청 허용 여부. true = 허용, false = 429 */
    override suspend fun tryConsume(
        clientId: String,
        maxTokens: Int,
        refillPerSecond: Int,
    ): Boolean {
        val result = redisTemplate.execute(
            rateLimitScript,
            listOf(RedisKeys.RateLimit.bucket(clientId)),
            listOf(
                maxTokens.toString(),
                refillPerSecond.toString(),
                System.currentTimeMillis().toString(),
            ),
        ).awaitSingle()

        return result == 1L
    }
}
```

---

## 테스트

### Testcontainers Redis 통합 테스트

```kotlin
@SpringBootTest
class RedisStockAdapterTest : IntegrationTestBase(), FunSpec({
    val adapter = autowired<RedisStockAdapter>()
    val redisTemplate = autowired<ReactiveStringRedisTemplate>()

    beforeEach {
        // 테스트 격리: 관련 키 삭제
        redisTemplate.delete(RedisKeys.Stock.remaining("prod-1")).awaitSingle()
    }

    test("재고 차감 성공") {
        // given
        redisTemplate.opsForValue()
            .set(RedisKeys.Stock.remaining("prod-1"), "100")
            .awaitSingle()

        // when
        val remaining = adapter.decrement("prod-1", 1)

        // then
        remaining shouldBe 99
    }

    test("재고 부족 시 -1 반환") {
        redisTemplate.opsForValue()
            .set(RedisKeys.Stock.remaining("prod-1"), "0")
            .awaitSingle()

        adapter.decrement("prod-1", 1) shouldBe -1
    }

    test("동시 차감 시 원자성 보장") {
        redisTemplate.opsForValue()
            .set(RedisKeys.Stock.remaining("prod-1"), "100")
            .awaitSingle()

        // 100개 동시 차감
        val results = (1..100).map {
            async { adapter.decrement("prod-1", 1) }
        }.awaitAll()

        // 100개 모두 성공 (0~99), 음수 없음
        results.filter { it >= 0 }.size shouldBe 100
        results.filter { it < 0 }.size shouldBe 0
    }
})
```

---

## 언제 어떤 패턴을 쓰는가?

| 상황 | 패턴 | 예시 |
|------|------|------|
| 여러 명령을 원자적으로 실행 | **Lua Script** | 재고 차감, Rate Limiting |
| 여러 서버의 동시 접근 제어 | **분산 락 (Redisson)** | 주문 처리, 결제 처리 |
| 순서가 중요한 대기열 | **Sorted Set** | 선착순 대기열 |
| 요청 수 제한 | **Token Bucket (Lua)** | API Rate Limiting |
| 단순 캐시 | **String GET/SET** | 재고 수량 조회, 멱등성 키 |
| 복합 데이터 캐시 | **Hash HSET/HGET** | 주문 상태, 사용자 세션 |
