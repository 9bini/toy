---
name: redis-setup
description: Redis 연산을 설정합니다. Lua Script, 분산 락(Redisson), Sorted Set 대기열, Token Bucket Rate Limiting 등을 구현합니다.
argument-hint: [operation-type lua-script|distributed-lock|sorted-set|rate-limiting|cache] [description]
---

$ARGUMENTS Redis 구성을 설정하세요.

## 연산 유형별 가이드

### lua-script (Lua 스크립트)
- 파일 위치: `{service}/src/main/resources/redis/{script-name}.lua`
- `ReactiveRedisTemplate`의 `execute(RedisScript, ...)` 로 실행
- 원자성 보장: 여러 Redis 커맨드를 하나의 Lua로 묶기

```lua
-- 예: 재고 차감 스크립트 (stock_decrement.lua)
local stock = redis.call('GET', KEYS[1])
if tonumber(stock) >= tonumber(ARGV[1]) then
    redis.call('DECRBY', KEYS[1], ARGV[1])
    return 1
else
    return 0
end
```

```kotlin
// Kotlin에서 Lua Script 실행
suspend fun decrementStock(productId: String, quantity: Int): Boolean {
    val script = RedisScript.of(ClassPathResource("redis/stock_decrement.lua"), Long::class.java)
    return reactiveRedisTemplate.execute(script, listOf("stock:$productId"), listOf(quantity.toString()))
        .awaitFirst() == 1L
}
```

**Lua Script 핵심 규칙:**
- KEYS와 ARGV 분리 필수 — KEYS는 Redis Cluster 라우팅에 사용
- 하나의 Lua Script 안에서 사용하는 모든 key는 KEYS로 전달
- ARGV는 값(파라미터)만 전달

### distributed-lock (분산 락)
- Redisson `RLockReactive` 사용
- 락 획득 타임아웃, 리스 타임아웃 반드시 설정

```kotlin
// ✅ 반드시 이 패턴을 따를 것
val lock = redissonClient.getLock("stock:lock:$productId")
val acquired = lock.tryLockAsync(waitTime, leaseTime, TimeUnit.MILLISECONDS).awaitSingle()
if (acquired) {
    try {
        // critical section
    } finally {
        // ✅ isHeldByCurrentThread 검사 필수 — 코루틴 스레드 전환 시 다른 스레드에서 unlock 시도하면 IllegalMonitorStateException
        if (lock.isHeldByCurrentThread) {
            lock.unlockAsync().awaitSingle()
        }
    }
} else {
    throw ConcurrencyException("Lock acquisition failed")
}
```

**분산 락 주의사항:**
- `tryLockAsync` 사용 (타임아웃 있음). `lockAsync`는 무한 대기 위험
- `finally` 블록에서 반드시 해제 — 예외 시 deadlock 방지
- `leaseTime = -1`: Watchdog 활성화 (10초마다 자동 갱신, 기본 30초 TTL)
- 명시적 `leaseTime` 설정 시 Watchdog 비활성화

### sorted-set (대기열)
- `ZADD`: 진입 시각을 score로 사용
- `ZRANGEBYSCORE`: 순서대로 조회
- `ZREM`: 처리 완료 후 제거
- TTL 기반 만료: 별도 스케줄러로 오래된 항목 정리

### rate-limiting (Token Bucket)
- Lua Script로 토큰 차감 + 리필 원자적 처리
- Gateway에서 요청별 실행
- 429 Too Many Requests 응답 연동

### cache (캐시)
- Spring Cache + Redis 설정
- `@Cacheable` / `@CacheEvict` 어노테이션
- TTL 전략 설정
- Cache-Aside 패턴

## 필수 사항
- 모든 Redis 연산은 Reactive/Coroutine 기반 (`awaitFirst()`, `awaitSingleOrNull()`)
- Lua Script는 반드시 원자성 테스트 작성 (동시 실행 시나리오)
- 통합 테스트는 Testcontainers Redis 사용
- **TTL 필수** — 모든 키에 TTL 설정 (미설정 시 메모리 누수)
- **키 네이밍**: `object RedisKeys`에 상수로 집중 관리
- `DEL` 대신 `UNLINK` 사용 (비동기 삭제, 대형 키에서 blocking 방지)
- Blocking 호출 절대 금지 — `awaitFirst()`, `awaitSingle()`, `awaitSingleOrNull()` 사용
