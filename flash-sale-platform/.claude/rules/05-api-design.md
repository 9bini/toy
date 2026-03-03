# REST API Design Rules

## WebFlux + Coroutines

### Controller 규칙
- 모든 핸들러는 `suspend fun` — blocking 코드 절대 금지
- 요청/응답 DTO는 Controller 레벨에서 정의 (domain 객체 직접 노출 금지)
- 검증은 Bean Validation 활용 (`@Valid`, `@field:NotBlank` 등)

### 응답 형식
```kotlin
// 성공
data class ApiResponse<T>(
    val success: Boolean = true,
    val data: T
)

// 에러
data class ErrorResponse(
    val success: Boolean = false,
    val error: ErrorDetail
)
data class ErrorDetail(
    val code: String,      // "ORDER_INSUFFICIENT_STOCK"
    val message: String    // 사용자용 메시지
)
```

### HTTP Status 매핑
| 상황 | Status | 예시 |
|------|--------|------|
| 정상 | 200 OK | 조회 성공 |
| 생성 | 201 Created | 주문 생성 |
| 입력 오류 | 400 Bad Request | 유효성 검증 실패 |
| 인증 실패 | 401 Unauthorized | 토큰 만료 |
| 권한 없음 | 403 Forbidden | 다른 사용자 주문 조회 |
| 리소스 없음 | 404 Not Found | 존재하지 않는 상품 |
| 비즈니스 규칙 위반 | 409 Conflict | 재고 부족, 중복 주문 |
| 요청 과다 | 429 Too Many Requests | Rate Limit 초과 |

### SSE (Server-Sent Events)
- 대기열 상태 알림은 SSE 사용
- 연결 타임아웃, 재연결 로직 필수
- Nginx SSE 프록시 설정과 정합성 확인
