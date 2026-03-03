# Data Integration Patterns (DB/Cache/Messaging)

## Redis 패턴

### 원자성 보장
- 모든 Redis 연산은 **Lua Script** 또는 **Redisson 분산 락**으로 원자성 보장
- 단순 GET/SET 외 복합 연산은 반드시 Lua Script 사용

### 키 관리
```kotlin
object RedisKeys {
    fun stock(productId: String) = "stock:product:$productId"
    fun queue(saleEventId: String) = "queue:sale:$saleEventId"
    fun rateLimit(userId: String) = "rate-limit:user:$userId"
}
```
- Redis 키 패턴은 `object RedisKeys`에 상수로 집중 관리
- 키에 사용자 입력 직접 삽입 금지 (Redis Injection 방지)

### 타임아웃
```kotlin
companion object {
    private val REDIS_OPERATION_TIMEOUT = 100.milliseconds  // 보통 1-5ms
    private val REDIS_LOCK_TIMEOUT = 5.seconds
}
```

## Kafka 패턴

### 멱등성 필수
- 모든 Consumer는 멱등하게 처리 (동일 메시지 재처리 시 부작용 없음)
- 멱등성 키: `{aggregate-type}:{aggregate-id}:{event-id}`

### 토픽 관리
- 토픽명은 상수 파일에 집중 관리
- 네이밍: `{domain}.{event-type}` (예: `order.placed`, `payment.completed`)

### DLQ (Dead Letter Queue)
- 3회 재시도 실패 시 DLQ로 이동
- DLQ 메시지는 알림 + 수동 처리

## DB (R2DBC) 패턴

### 트랜잭션
- `@Transactional` 사용 시 코루틴 컨텍스트 주의
- 분산 트랜잭션은 Saga 패턴 사용 (2PC 금지)

### 외부 호출 공통
- 모든 외부 I/O에 `withTimeout` 필수
- 재시도 시 exponential backoff 적용
- Circuit Breaker 패턴 권장 (장애 전파 차단)
