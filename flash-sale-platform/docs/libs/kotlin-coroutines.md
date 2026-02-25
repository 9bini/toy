# Kotlin Coroutines

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [핵심 개념](#3-핵심-개념)
4. [코루틴 빌더](#4-코루틴-빌더)
5. [Flow](#5-flow)
6. [예외 처리](#6-예외-처리)
7. [Reactor 변환](#7-reactor-변환)
8. [이 프로젝트에서의 활용](#8-이-프로젝트에서의-활용)
9. [자주 하는 실수 / 주의사항](#9-자주-하는-실수--주의사항)
10. [정리 / 한눈에 보기](#10-정리--한눈에-보기)
11. [더 알아보기](#11-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

I/O 작업 중 **스레드를 반납하고 다른 일을 할 수 있게** 해주는 경량 비동기 프로그래밍 라이브러리.

### 비유: 식당 종업원

**스레드 기반 (블로킹)**:
- 종업원 1명이 주문 받으면, 요리 나올 때까지 그 테이블 앞에서 기다림
- 10개 테이블 = 종업원 10명 필요

**코루틴 기반 (논블로킹)**:
- 종업원 1명이 주문 받고 → 주방에 전달 → 다른 테이블 주문 받음 → 요리 나오면 서빙
- 10개 테이블 = 종업원 2~3명이면 충분

### 스레드 vs 코루틴

| | 스레드 | 코루틴 |
|---|---|---|
| 크기 | ~1MB (스택 메모리) | ~수 KB |
| 생성 비용 | 높음 (OS 레벨) | 낮음 (유저 레벨) |
| 동시 수 | 수천 개 (메모리 한계) | **수십만 개** |
| 전환 비용 | 높음 (컨텍스트 스위칭) | 낮음 (일시 정지/재개) |
| I/O 대기 | 스레드 점유 | **스레드 반납** |

---

## 2. 왜 필요한가?

### Before: 블로킹 방식 (Spring MVC)

```kotlin
// 스레드 200개 (Tomcat 기본)
@GetMapping("/orders/{id}")
fun getOrder(@PathVariable id: String): Order {
    val order = orderRepository.findById(id)    // 10ms 블로킹 (스레드 점유)
    val payment = paymentClient.getPayment(id)  // 50ms 블로킹 (스레드 점유)
    return Order(order, payment)
}

// 동시 접속 200명 → 스레드 200개 전부 사용
// 동시 접속 201명 → 대기 (스레드 부족)
// 동시 접속 10,000명 → 불가능
```

### After: 코루틴 방식 (Spring WebFlux)

```kotlin
// 스레드 ~8개 (CPU 코어 수)
@GetMapping("/orders/{id}")
suspend fun getOrder(@PathVariable id: String): Order {
    val order = orderRepository.findById(id)    // 10ms 대기하는 동안 스레드 반납
    val payment = paymentClient.getPayment(id)  // 50ms 대기하는 동안 스레드 반납
    return Order(order, payment)
}

// 동시 접속 10,000명 → 8개 스레드로 처리 가능!
// (I/O 대기 중 스레드를 다른 요청에 할당)
```

---

## 3. 핵심 개념

### 3.1 suspend fun (일시 정지 함수)

`suspend` 키워드가 붙은 함수는 **실행 중간에 일시 정지**할 수 있다.

```kotlin
// 일반 함수: 호출하면 끝날 때까지 스레드를 점유
fun normalFunction(): String { ... }

// suspend 함수: 중간에 일시 정지 가능 (스레드 반납)
suspend fun suspendFunction(): String { ... }
```

```
suspend fun 실행 흐름:

1. DB 쿼리 시작 (스레드 A에서 실행)
2. → DB 응답 대기 (스레드 A 반납! 다른 코루틴이 사용)
3. → DB 응답 도착 (스레드 B에서 재개, A가 아닐 수 있음)
4. → 결과 반환
```

### 3.2 Coroutine Scope (코루틴 스코프)

코루틴의 **생명주기**를 관리하는 범위. 스코프가 취소되면 내부 코루틴도 전부 취소.

```kotlin
// 컨트롤러: Spring이 요청마다 자동으로 스코프 제공
@GetMapping("/orders")
suspend fun getOrders(): List<Order> {
    // 이 함수가 끝나면 (또는 요청이 취소되면) 내부 코루틴도 취소
    return orderService.findAll()
}

// 직접 스코프 생성
coroutineScope {
    val a = async { fetchA() }    // 자식 코루틴 1
    val b = async { fetchB() }    // 자식 코루틴 2
    a.await() + b.await()         // 둘 다 완료 대기
}   // 스코프 종료: 자식이 모두 완료될 때까지 대기
```

### 3.3 Dispatcher (디스패처)

코루틴이 **어떤 스레드에서 실행**될지 결정한다.

```kotlin
withContext(Dispatchers.Default) {    // CPU 작업용 스레드 풀
    // 복잡한 계산
}

withContext(Dispatchers.IO) {         // I/O 작업용 스레드 풀 (블로킹 허용)
    // 블로킹 라이브러리 호출 (JDBC 등)
}

withContext(Dispatchers.Unconfined) {  // 호출한 스레드에서 바로 실행
    // 특수한 경우만 사용
}
```

| 디스패처 | 스레드 풀 크기 | 용도 |
|---------|-------------|------|
| `Default` | CPU 코어 수 | 계산, 정렬, JSON 파싱 |
| `IO` | 64개 (또는 코어 수 중 큰 값) | 블로킹 I/O, JDBC |
| `Unconfined` | 없음 | 테스트, 특수 상황 |

### 3.4 구조적 동시성 (Structured Concurrency)

부모 코루틴이 **자식 코루틴의 완료를 보장**하는 원칙.

```kotlin
coroutineScope {        // 부모 스코프
    launch { taskA() }  // 자식 1
    launch { taskB() }  // 자식 2
}
// ← 자식 1, 2 모두 완료된 후에야 여기에 도달

// 자식 중 하나가 실패하면 → 나머지 자식도 취소 → 부모도 실패
```

**supervisorScope**: 자식 하나가 실패해도 **나머지는 계속 실행**

```kotlin
supervisorScope {
    launch { sendEmail() }   // 실패해도
    launch { sendPush() }    // 계속 실행됨
}
```

### 3.5 withTimeout (타임아웃)

지정 시간 안에 완료되지 않으면 코루틴을 취소한다.

```kotlin
// 100ms 안에 완료 안 되면 TimeoutCancellationException 발생
val result = withTimeout(100.milliseconds) {
    redisClient.get(key)
}

// 타임아웃 시 null 반환 (예외 없음)
val result = withTimeoutOrNull(100.milliseconds) {
    redisClient.get(key)
}
```

---

## 4. 코루틴 빌더

### 4.1 launch — "실행하고 잊기"

결과를 반환하지 않는 코루틴. 반환 타입 `Job`.

```kotlin
coroutineScope {
    val job = launch {
        delay(1000)
        println("완료!")
    }
    // job.cancel()      // 취소 가능
    // job.join()         // 완료 대기
}
```

### 4.2 async — "결과를 돌려받기"

결과를 반환하는 코루틴. 반환 타입 `Deferred<T>`.

```kotlin
coroutineScope {
    val orderDeferred = async { fetchOrder(id) }      // 동시 시작
    val paymentDeferred = async { fetchPayment(id) }  // 동시 시작

    val order = orderDeferred.await()     // 결과 대기
    val payment = paymentDeferred.await() // 결과 대기

    // 두 작업이 병렬 실행 → 총 시간 = max(order시간, payment시간)
}
```

### 4.3 coroutineScope — "자식 완료 보장"

```kotlin
suspend fun process() = coroutineScope {
    launch { taskA() }
    launch { taskB() }
    // taskA, taskB 모두 완료 후 반환
    // 하나라도 실패하면 나머지 취소 후 예외 전파
}
```

### 4.4 supervisorScope — "실패 격리"

```kotlin
suspend fun sendNotifications() = supervisorScope {
    launch { sendEmail() }  // 실패해도
    launch { sendSms() }    // 영향 없음
    launch { sendPush() }   // 영향 없음
}
```

---

## 5. Flow

### 여러 값을 순차적으로 방출하는 비동기 스트림

`suspend fun`이 하나의 값을 반환한다면, `Flow`는 **여러 값을 순차적으로** 방출한다.

```kotlin
// suspend fun: 1개의 값
suspend fun getOrder(): Order

// Flow: N개의 값 (스트림)
fun getOrderUpdates(): Flow<OrderUpdate>
```

### 기본 사용법

```kotlin
// Flow 생성
fun orderUpdates(orderId: String): Flow<String> = flow {
    emit("PENDING")     // 값 방출
    delay(1000)
    emit("PROCESSING")
    delay(1000)
    emit("COMPLETED")
}

// Flow 수집 (소비)
orderUpdates("order-1").collect { status ->
    println("상태: $status")
}
// 출력:
// 상태: PENDING
// 상태: PROCESSING
// 상태: COMPLETED
```

### 주요 연산자

```kotlin
flow
    .map { it.toUpperCase() }            // 변환
    .filter { it != "CANCELLED" }        // 필터링
    .onEach { logger.info { "상태: $it" } }  // 부수 효과
    .catch { e -> emit("ERROR") }        // 에러 처리
    .collect { println(it) }             // 최종 소비
```

### SSE와 Flow

```kotlin
@GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
fun streamEvents(): Flow<ServerSentEvent<String>> = flow {
    while (true) {
        val update = getLatestUpdate()
        emit(ServerSentEvent.builder(update).build())
        delay(1.seconds)
    }
}
```

---

## 6. 예외 처리

### try-catch (일반적)

```kotlin
suspend fun safeCall(): Result<Order> {
    return try {
        Result.success(orderService.findById(id))
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

### CancellationException은 잡지 않기

```kotlin
// ❌ CancellationException을 삼키면 코루틴 취소가 안 됨
try {
    suspendFunction()
} catch (e: Exception) {    // CancellationException도 잡힘!
    logger.error { "에러" }
}

// ✅ CancellationException은 다시 던지기
try {
    suspendFunction()
} catch (e: CancellationException) {
    throw e                 // 재던짐
} catch (e: Exception) {
    logger.error { "에러" }
}
```

---

## 7. Reactor 변환

WebFlux의 Reactor 타입과 코루틴 간 변환이 필요하다.

### Mono ↔ suspend

```kotlin
// Mono<T> → T (suspend)
val order: Order = mono.awaitSingle()
val order: Order? = mono.awaitSingleOrNull()

// suspend → Mono<T>
val mono: Mono<Order> = mono { findOrder(id) }
```

### Flux ↔ Flow

```kotlin
// Flux<T> → Flow<T>
val flow: Flow<Order> = flux.asFlow()

// Flow<T> → Flux<T>
val flux: Flux<Order> = flow.asFlux()
```

---

## 8. 이 프로젝트에서의 활용

### 사용하는 모듈

| 모듈 | 역할 |
|------|------|
| `kotlinx-coroutines-core` | 핵심 (launch, async, Flow) |
| `kotlinx-coroutines-reactor` | Reactor ↔ 코루틴 변환 |
| `kotlinx-coroutines-slf4j` | 로깅 컨텍스트(MDC) 전파 |
| `kotlinx-coroutines-test` | 테스트 (runTest) |

### 의존성

```kotlin
// build.gradle.kts (루트 subprojects)
implementation(rootProject.libs.bundles.coroutines)  // core + reactor + slf4j
testImplementation(rootProject.libs.coroutines.test)
```

### 프로젝트 코드 패턴

```kotlin
// Controller: suspend fun
@PostMapping
suspend fun placeOrder(@RequestBody request: OrderRequest): ResponseEntity<...> {
    return useCase.execute(request).fold(
        onSuccess = { ResponseEntity.ok(it) },
        onFailure = { ResponseEntity.badRequest().build() }
    )
}

// UseCase: suspend fun
class PlaceOrderUseCase {
    suspend fun execute(request: OrderRequest): Result<Order> {
        val stock = withTimeout(100.milliseconds) {
            stockPort.getRemaining(request.productId)
        }
        // ...
    }
}

// Adapter: Reactor → suspend 변환
class RedisStockAdapter : StockPort {
    override suspend fun getRemaining(productId: String): Long {
        return redisTemplate.opsForValue()
            .get(RedisKeys.Stock.remaining(productId))
            .awaitSingleOrNull()?.toLong() ?: 0L
    }
}
```

---

## 9. 자주 하는 실수 / 주의사항

### Thread.sleep() 사용

```kotlin
// ❌ 스레드를 블로킹함 (코루틴의 의미 없음)
Thread.sleep(1000)

// ✅ 코루틴 일시 정지 (스레드 반납)
delay(1000)
```

### GlobalScope 사용

```kotlin
// ❌ 생명주기 관리 안 됨 (메모리 누수 가능)
GlobalScope.launch { doSomething() }

// ✅ 구조적 동시성
coroutineScope {
    launch { doSomething() }
}
```

### 블로킹 I/O 직접 호출

```kotlin
// ❌ 코루틴 디스패처에서 블로킹 → 스레드 고갈
suspend fun readFile(): String {
    return File("data.txt").readText()    // 블로킹!
}

// ✅ IO 디스패처로 전환
suspend fun readFile(): String = withContext(Dispatchers.IO) {
    File("data.txt").readText()
}
```

### runBlocking 남용

```kotlin
// ❌ 코루틴 안에서 runBlocking → 데드락 가능
suspend fun bad() {
    runBlocking { otherSuspendFun() }
}

// ✅ 그냥 suspend fun 호출
suspend fun good() {
    otherSuspendFun()
}
```

---

## 10. 정리 / 한눈에 보기

### 핵심 키워드

| 키워드/함수 | 역할 |
|------------|------|
| `suspend` | 일시 정지 가능한 함수 표시 |
| `launch` | 결과 없는 코루틴 시작 |
| `async` | 결과 있는 코루틴 시작 |
| `await()` | async의 결과 대기 |
| `delay()` | 논블로킹 대기 |
| `withTimeout()` | 타임아웃 설정 |
| `withContext()` | 디스패처 전환 |
| `coroutineScope` | 자식 완료 보장 |
| `supervisorScope` | 실패 격리 |
| `Flow` | 비동기 스트림 |

### Reactor 변환 치트시트

| 변환 | 코드 |
|------|------|
| `Mono<T>` → `T` | `mono.awaitSingle()` |
| `Mono<T>` → `T?` | `mono.awaitSingleOrNull()` |
| `Flux<T>` → `Flow<T>` | `flux.asFlow()` |
| `Flow<T>` → `Flux<T>` | `flow.asFlux()` |

---

## 11. 더 알아보기

- [Kotlin 코루틴 공식 가이드](https://kotlinlang.org/docs/coroutines-guide.html)
- [kotlinx.coroutines GitHub](https://github.com/Kotlin/kotlinx.coroutines)
