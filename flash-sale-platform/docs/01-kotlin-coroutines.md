# 1. Kotlin Coroutines (코루틴)

> **한 줄 요약**: 스레드를 차지하지 않고 "일시 정지"할 수 있는 경량 비동기 프로그래밍 모델

---

## 왜 코루틴이 필요한가?

### 전통적인 방식의 문제

```kotlin
// 블로킹 코드: 스레드가 DB 응답을 기다리며 아무것도 못 함
fun getOrder(orderId: String): Order {
    val order = orderRepository.findById(orderId) // 스레드가 여기서 멈춤 (50ms)
    val payment = paymentApi.getPayment(order.paymentId) // 또 멈춤 (200ms)
    return order.copy(payment = payment) // 총 250ms 동안 스레드 1개 점유
}
```

10만 명이 동시에 요청하면? → 10만 개의 스레드 필요 → 메모리 폭발 (스레드 1개 ≈ 1MB)

### 코루틴의 해결 방식

```kotlin
// 코루틴: 기다리는 동안 스레드를 반납하고 다른 일을 처리
suspend fun getOrder(orderId: String): Order {
    val order = orderRepository.findById(orderId) // 기다리는 동안 스레드 반납
    val payment = paymentApi.getPayment(order.paymentId) // 기다리는 동안 스레드 반납
    return order.copy(payment = payment) // 스레드 점유 시간: 실제 CPU 작업만큼만
}
```

10만 명이 동시에 요청해도 → 소수의 스레드로 처리 가능 (코루틴은 메모리 수백 바이트)

---

## 핵심 개념

### 1. `suspend fun` (일시 정지 함수)

```kotlin
// suspend 키워드 = "이 함수는 일시 정지될 수 있다"는 표시
suspend fun fetchStock(productId: String): Int {
    // 네트워크 I/O 동안 스레드를 반납하고 기다림
    return redisTemplate.opsForValue().get("stock:$productId")?.toInt() ?: 0
}
```

**규칙**: `suspend fun`은 다른 `suspend fun` 또는 코루틴 빌더 안에서만 호출 가능

```kotlin
// ✅ suspend fun 안에서 호출
suspend fun processOrder() {
    val stock = fetchStock("product-1") // OK
}

// ❌ 일반 함수에서 직접 호출 불가
fun processOrder() {
    val stock = fetchStock("product-1") // 컴파일 에러!
}
```

### 2. 코루틴 빌더

```kotlin
// launch: 결과가 필요 없는 비동기 작업 ("발사하고 잊기")
launch {
    sendNotification(userId, message)
}

// async: 결과가 필요한 비동기 작업
val stockDeferred = async { fetchStock(productId) }
val priceDeferred = async { fetchPrice(productId) }
// 두 작업이 동시에 실행되고, await()으로 결과를 받음
val stock = stockDeferred.await()
val price = priceDeferred.await()
```

### 3. 구조적 동시성 (Structured Concurrency)

```kotlin
suspend fun placeOrder(request: OrderRequest) {
    // coroutineScope: 내부의 모든 코루틴이 끝나야 함수가 끝남
    // 하나가 실패하면 나머지도 취소됨
    coroutineScope {
        val stockJob = async { decrementStock(request.productId) }
        val paymentJob = async { processPayment(request.paymentInfo) }

        // 둘 다 완료될 때까지 대기
        val stockResult = stockJob.await()
        val paymentResult = paymentJob.await()
    }
    // 여기 도달하면 모든 하위 코루틴이 완료된 상태
}
```

### 4. `withTimeout` (타임아웃 설정)

```kotlin
suspend fun getStockWithTimeout(productId: String): Int {
    return withTimeout(100.milliseconds) {
        // 100ms 안에 응답이 없으면 TimeoutCancellationException 발생
        redisTemplate.opsForValue().get("stock:$productId")?.toInt() ?: 0
    }
}
```

---

## 이 프로젝트에서의 활용

### 모든 I/O 함수는 `suspend fun`

이 프로젝트의 **코드 컨벤션**에서 모든 I/O 함수는 반드시 `suspend fun`으로 선언합니다.
블로킹 코드는 금지입니다.

```kotlin
// ✅ 프로젝트 코드 스타일
interface StockPort {
    suspend fun getRemaining(productId: String): Int
    suspend fun decrement(productId: String, quantity: Int): Boolean
}

// ❌ 블로킹 코드 금지
interface StockPort {
    fun getRemaining(productId: String): Int  // 블로킹!
}
```

### 타임아웃 상수 관리

프로젝트에서는 `TimeoutProperties`로 모든 타임아웃을 중앙 관리합니다.

```kotlin
// common/infrastructure 모듈의 Timeouts.kt에서 발췌
@ConfigurationProperties(prefix = "flashsale.timeout")
data class TimeoutProperties(
    val redisOperationMs: Long = 100,     // Redis 단순 연산: 100ms
    val redisLuaScriptMs: Long = 200,     // Lua Script: 200ms
    val distributedLockWaitMs: Long = 3000, // 분산 락 대기: 3초
    val paymentApiMs: Long = 3000,         // 결제 API: 3초
    val dbQueryMs: Long = 2000,            // DB 쿼리: 2초
) {
    val redisOperation: Duration get() = redisOperationMs.milliseconds
    val paymentApi: Duration get() = paymentApiMs.milliseconds
    // ...
}
```

사용 시:
```kotlin
class RedisStockAdapter(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val timeouts: TimeoutProperties,
) : StockPort {
    override suspend fun getRemaining(productId: String): Int =
        withTimeout(timeouts.redisOperation) {
            redisTemplate.opsForValue()
                .get(RedisKeys.Stock.remaining(productId))
                .awaitSingleOrNull()
                ?.toInt() ?: 0
        }
}
```

### 병렬 실행이 필요한 경우

```kotlin
suspend fun enrichOrderDetails(orderId: String): OrderDetails {
    // 재고 조회와 결제 정보 조회를 동시에 실행 (독립적인 작업이므로)
    return coroutineScope {
        val stock = async { stockPort.getRemaining(productId) }
        val payment = async { paymentPort.getStatus(paymentId) }

        OrderDetails(
            stock = stock.await(),
            paymentStatus = payment.await()
        )
    }
}
```

---

## 코루틴 vs 스레드 비교

| 항목 | 스레드 | 코루틴 |
|------|--------|--------|
| 메모리 | ~1MB/개 | ~수백 바이트/개 |
| 10만 동시 처리 | 100GB 메모리 필요 | 수십 MB로 충분 |
| 컨텍스트 스위칭 | OS 커널 레벨 (비용 큼) | 유저 레벨 (비용 작음) |
| I/O 대기 중 | 스레드 점유 (낭비) | 스레드 반납 (효율적) |
| 취소 | 복잡 (interrupt) | 구조적 동시성으로 자동 |

---

## 주의사항

### 1. 블로킹 호출 금지

```kotlin
// ❌ 코루틴 안에서 블로킹 호출 = 스레드 풀 고갈
suspend fun bad() {
    Thread.sleep(1000) // 절대 금지!
    java.io.File("data.txt").readText() // 절대 금지!
}

// ✅ 반드시 비동기 API 사용
suspend fun good() {
    delay(1000) // 코루틴의 일시 정지
    // 파일 I/O가 필요하면 withContext(Dispatchers.IO) 사용
}
```

### 2. GlobalScope 사용 금지

```kotlin
// ❌ GlobalScope: 생명주기 관리 불가, 메모리 누수 위험
GlobalScope.launch { doSomething() }

// ✅ 구조적 동시성 사용
coroutineScope {
    launch { doSomething() }
}
```

### 3. supervisorScope 활용

```kotlin
// 하나의 실패가 다른 작업에 영향을 주면 안 되는 경우
supervisorScope {
    launch { sendEmailNotification() }  // 실패해도
    launch { sendPushNotification() }   // 이건 계속 실행
}
```

---

## 더 알아보기

- **공식 문서**: [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)
- **핵심 라이브러리**: `kotlinx-coroutines-core`, `kotlinx-coroutines-reactor` (WebFlux 연동)
- **이 프로젝트 관련 파일**: `common/infrastructure/src/.../config/Timeouts.kt`
