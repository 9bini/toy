# Redisson

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [핵심 개념: 분산 락](#3-핵심-개념-분산-락)
4. [RLock API](#4-rlock-api)
5. [Watchdog](#5-watchdog)
6. [이 프로젝트에서의 활용](#6-이-프로젝트에서의-활용)
7. [자주 하는 실수 / 주의사항](#7-자주-하는-실수--주의사항)
8. [정리 / 한눈에 보기](#8-정리--한눈에-보기)
9. [더 알아보기](#9-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

Redis 위에 **분산 락, 분산 자료구조** 등 고수준 기능을 제공하는 Java/Kotlin Redis 클라이언트.

### 비유: 일반 자물쇠 vs 스마트 자물쇠

- **Redis SETNX (일반 자물쇠)**: 잠그고 열 수 있지만, 열쇠를 잃어버리면 영원히 잠김
- **Redisson RLock (스마트 자물쇠)**: 자동 타이머, 열쇠 분실 대비, 재진입 가능

### Spring Data Redis (Lettuce) vs Redisson

| | Lettuce (Spring 기본) | Redisson |
|---|---|---|
| 역할 | Redis 기본 명령 (GET, SET, ZADD) | **분산 자료구조** (Lock, Semaphore) |
| 분산 락 | 직접 구현 필요 (SETNX + Lua) | **RLock 제공** |
| 자동 만료 | 직접 구현 | **Watchdog** 자동 연장 |
| 재진입 | 미지원 | **지원** |
| 비동기 API | Mono/Flux | Mono/Flux + **코루틴** |

이 프로젝트에서는 **Lettuce + Redisson 둘 다 사용**:
- Lettuce: 재고 조회, 대기열, 캐시 (기본 Redis 명령)
- Redisson: **분산 락** (동시성 제어)

---

## 2. 왜 필요한가?

### 분산 환경의 동시성 문제

서버가 여러 대일 때, 같은 데이터를 동시에 수정하면 문제가 생긴다.

```
서버 A: 재고 조회 (남은 1개) → 주문 승인 → 재고 차감
서버 B: 재고 조회 (남은 1개) → 주문 승인 → 재고 차감
결과: 재고 1개인데 2개 팔림! (Overselling)
```

### Java의 synchronized는 안 됨

```kotlin
// ❌ synchronized는 같은 JVM(서버) 안에서만 유효
synchronized(this) {
    // 서버 A의 이 블록과 서버 B의 이 블록은 독립적!
    checkAndDecrementStock()
}
```

### Redis 분산 락으로 해결

```
서버 A: 락 획득 → 재고 조회(1개) → 주문 승인 → 재고 차감 → 락 해제
서버 B: 락 시도 → 대기... → 락 획득 → 재고 조회(0개) → 주문 거절 → 락 해제
결과: 정확히 1개만 팔림!
```

---

## 3. 핵심 개념: 분산 락

### 3.1 분산 락이란?

여러 서버(프로세스)가 **공유 자원에 동시에 접근하는 것을 방지**하는 메커니즘.
Redis를 "자물쇠 보관함"으로 사용한다.

```
Redis에 특정 키 설정 = 자물쇠 잠금
Redis에서 키 삭제 = 자물쇠 해제

서버 A: SET lock:stock:prod-1 "server-a" NX EX 5
  → 성공 (락 획득, 5초 후 자동 만료)

서버 B: SET lock:stock:prod-1 "server-b" NX EX 5
  → 실패 (이미 잠겨 있음, 대기)
```

### 3.2 단순 SETNX의 문제점

```
문제 1: 서버가 락을 잡고 죽으면?
  → 만료 시간(EX) 없으면 영원히 잠김 (데드락)
  → 만료 시간 있으면 작업 중에 만료될 수 있음

문제 2: 작업이 만료 시간보다 오래 걸리면?
  서버 A: 락 획득 (5초) → 작업 7초 → 5초 시점에 만료!
  서버 B: 5초 후 락 획득 → A와 B가 동시에 작업!

문제 3: 재진입 불가
  같은 스레드가 같은 락을 다시 잡으려 하면 → 데드락
```

### 3.3 Redisson이 해결하는 방법

| 문제 | Redisson 해결책 |
|------|---------------|
| 데드락 (서버 죽음) | leaseTime 자동 만료 |
| 작업 중 만료 | **Watchdog** 자동 연장 |
| 재진입 불가 | **Reentrant Lock** 지원 |
| 비동기 미지원 | `tryLockAsync()` 코루틴 호환 |

---

## 4. RLock API

### 기본 사용법

```kotlin
val redissonClient: RedissonClient

// 락 객체 생성 (아직 잠그지 않음)
val lock: RLock = redissonClient.getLock("order:lock:$orderId")

// 락 획득 시도
val acquired: Boolean = lock.tryLockAsync(
    3000,  // waitTime: 최대 3초 대기 (못 잡으면 false)
    5000,  // leaseTime: 5초 후 자동 해제 (데드락 방지)
    TimeUnit.MILLISECONDS
).awaitSingle()

if (acquired) {
    try {
        // === 이 블록은 한 번에 하나의 서버만 실행 ===
        processOrder(orderId)
    } finally {
        // 반드시 해제 (finally 블록!)
        if (lock.isHeldByCurrentThread) {
            lock.unlockAsync().awaitSingle()
        }
    }
} else {
    // 3초 안에 락을 못 잡음 → 다른 처리
    throw ConcurrencyException("락 획득 실패")
}
```

### 주요 메서드

| 메서드 | 설명 |
|--------|------|
| `getLock(name)` | 락 객체 생성 |
| `tryLockAsync(wait, lease, unit)` | 비동기 락 획득 시도 |
| `lockAsync(lease, unit)` | 비동기 락 획득 (무한 대기) |
| `unlockAsync()` | 비동기 락 해제 |
| `isHeldByCurrentThread` | 현재 스레드가 락을 보유 중? |
| `isLocked` | 누군가가 락을 보유 중? |

### tryLock vs lock

```kotlin
// tryLock: 대기 시간 제한 (타임아웃)
lock.tryLockAsync(3000, 5000, MILLISECONDS)
// → 3초 안에 못 잡으면 false 반환

// lock: 무한 대기 (잡을 때까지)
lock.lockAsync(5000, MILLISECONDS)
// → 잡을 때까지 무한 대기 (위험!)
```

---

## 5. Watchdog

### leaseTime을 설정하지 않으면 Watchdog 활성화

```kotlin
// leaseTime = -1 (기본) → Watchdog 활성화
lock.tryLockAsync(3000, -1, MILLISECONDS)

// Watchdog 동작:
// 1. 기본 30초로 락 설정
// 2. 10초마다 락이 아직 필요한지 확인
// 3. 필요하면 30초로 다시 연장
// 4. 작업 완료 (unlock) 또는 서버 죽음 → 연장 중지 → 자동 만료
```

### leaseTime을 설정하면 Watchdog 비활성화

```kotlin
// leaseTime = 5000 → Watchdog 비활성화
lock.tryLockAsync(3000, 5000, MILLISECONDS)
// → 정확히 5초 후 자동 만료 (연장 없음)
```

| | Watchdog (leaseTime 미설정) | 고정 leaseTime |
|---|---|---|
| 만료 시간 | 30초 (자동 연장) | 지정한 시간 (연장 없음) |
| 작업이 길어지면 | 자동 연장됨 | **만료될 수 있음** |
| 서버 죽으면 | 30초 후 자동 해제 | 지정 시간 후 자동 해제 |

---

## 6. 이 프로젝트에서의 활용

### 재고 차감 시 분산 락

```kotlin
class RedisStockAdapter(
    private val redissonClient: RedissonClient,
) {
    suspend fun decrementWithLock(productId: String, quantity: Int): Result<Long> {
        val lock = redissonClient.getLock("stock:lock:$productId")

        val acquired = lock.tryLockAsync(3000, 5000, TimeUnit.MILLISECONDS)
            .awaitSingle()

        if (!acquired) return Result.failure(ConcurrencyException("재고 락 획득 실패"))

        return try {
            // Lua Script로 원자적 차감
            val remaining = decrementStock(productId, quantity)
            if (remaining >= 0) Result.success(remaining)
            else Result.failure(InsufficientStockException())
        } finally {
            if (lock.isHeldByCurrentThread) {
                lock.unlockAsync().awaitSingle()
            }
        }
    }
}
```

### 의존성

```kotlin
// common/infrastructure/build.gradle.kts
implementation(libs.redisson.spring.boot.starter)

// libs.versions.toml
redisson = "3.40.2"
redisson-spring-boot-starter = { module = "org.redisson:redisson-spring-boot-starter", version.ref = "redisson" }
```

---

## 7. 자주 하는 실수 / 주의사항

### unlock 빼먹기

```kotlin
// ❌ 예외 발생 시 unlock이 안 됨 → 데드락!
lock.tryLockAsync(3000, 5000, MILLISECONDS).awaitSingle()
processOrder()  // 여기서 예외 발생하면?
lock.unlockAsync().awaitSingle()  // 실행 안 됨!

// ✅ finally 블록에서 해제
try {
    processOrder()
} finally {
    if (lock.isHeldByCurrentThread) {
        lock.unlockAsync().awaitSingle()
    }
}
```

### 다른 스레드에서 unlock

```kotlin
// ❌ 락을 잡은 스레드가 아닌 다른 스레드에서 해제 시도 → IllegalMonitorStateException
// (코루틴에서 스레드 전환 시 발생 가능)

// ✅ isHeldByCurrentThread 확인 후 해제
if (lock.isHeldByCurrentThread) {
    lock.unlockAsync().awaitSingle()
}
```

### leaseTime이 너무 짧음

```kotlin
// ❌ 작업이 1초인데 leaseTime이 500ms → 작업 중 만료!
lock.tryLockAsync(3000, 500, MILLISECONDS)

// ✅ 작업 시간 + 여유를 고려
lock.tryLockAsync(3000, 5000, MILLISECONDS)
// 또는 Watchdog 사용 (leaseTime = -1)
```

---

## 8. 정리 / 한눈에 보기

### 분산 락 사용 패턴

```kotlin
val lock = redissonClient.getLock("key")
val acquired = lock.tryLockAsync(waitTime, leaseTime, unit).awaitSingle()
if (acquired) {
    try {
        // 임계 영역 (한 서버만 실행)
    } finally {
        if (lock.isHeldByCurrentThread) lock.unlockAsync().awaitSingle()
    }
}
```

### 핵심 요약

| 개념 | 설명 |
|------|------|
| 분산 락 | 여러 서버에서 동시 접근 방지 |
| RLock | Redisson의 분산 락 구현 |
| waitTime | 락 획득 최대 대기 시간 |
| leaseTime | 락 자동 만료 시간 |
| Watchdog | leaseTime 자동 연장 (설정 안 하면 활성) |
| Reentrant | 같은 스레드가 같은 락 재획득 가능 |

---

## 9. 더 알아보기

- [Redisson 공식 문서](https://redisson.org/)
- [Redisson GitHub Wiki](https://github.com/redisson/redisson/wiki)
