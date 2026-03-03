# Error Handling Protocol

## Sealed Class 에러 정의

각 서비스는 `domain` 패키지에 sealed interface로 에러 타입 정의:

```kotlin
sealed interface OrderError {
    /** 상품 재고가 요청 수량보다 부족할 때 */
    data class InsufficientStock(val available: Int, val requested: Int) : OrderError
    /** 결제 게이트웨이 타임아웃 (3초 초과) */
    data class PaymentTimeout(val orderId: String) : OrderError
    /** 이미 처리된 주문을 재처리 시도할 때 */
    data class DuplicateOrder(val orderId: String) : OrderError
}
```

### 규칙
- 각 에러 타입에 KDoc으로 **발생 조건** 설명 필수
- 에러 데이터에 디버깅에 필요한 컨텍스트 포함
- 범용 에러(`GenericError`, `UnknownError`) 사용 금지 — 구체적 타입 정의

## 에러 전파 패턴

```
UseCase (sealed class) → Controller (HTTP Status 매핑) → Client (ErrorResponse)
```

- UseCase는 `Result<T>` 또는 sealed class 반환 (Exception throw 금지)
- Controller에서 sealed class → HTTP Status + ErrorResponse 변환
- 외부 호출 예외는 UseCase에서 catch → sealed class 변환

## 타임아웃 에러

```kotlin
companion object {
    private val REDIS_OPERATION_TIMEOUT = 100.milliseconds
    private val PAYMENT_API_TIMEOUT = 3.seconds
    private val KAFKA_SEND_TIMEOUT = 5.seconds
}
```

- 타임아웃 값은 상수로 정의, 이름에 의도 포함
- `withTimeout` 초과 시 전용 에러 타입으로 변환

## 로깅 규칙
- **ERROR**: 즉시 대응 필요 (결제 실패, 데이터 정합성 깨짐)
- **WARN**: 비정상이지만 자동 복구 (재시도 성공, Rate Limit 초과)
- **INFO**: 비즈니스 이벤트 (주문 생성, 결제 완료)
- **DEBUG**: 디버깅용 (개발 환경에서만 활성화)
