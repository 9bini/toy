# Security Patterns

## Flash Sale 특화 보안 위협

### Race Condition 방지
- 재고 차감은 반드시 Redis Lua Script (atomic decrement)
- 분산 락 사용 시 Redisson (WATCH/MULTI 금지)
- 동일 사용자 중복 주문 방지 — 멱등성 키 + TTL

### Rate Limiting 우회 방지
- IP + User-Agent + Token 복합 식별
- Token Bucket 알고리즘 (Redis Lua Script)
- Nginx 레벨 + Application 레벨 이중 방어

### 대기열 조작 방지
- Sorted Set score는 서버 타임스탬프 (클라이언트 값 사용 금지)
- 대기열 토큰은 서버에서 발급 (추측 불가능한 값)

## 입력 검증

### 필수 검증 항목
- 모든 API 입력에 Bean Validation 적용
- Redis 키에 사용자 입력 직접 삽입 금지 (sanitize 필수)
- 숫자 파라미터 범위 검증 (quantity > 0, quantity <= MAX_QUANTITY)

### IDOR (Insecure Direct Object Reference) 방지
- 리소스 접근 시 항상 소유권 확인
- `orderId`로 조회 시 요청자의 주문인지 검증
- 내부 순차 ID 노출 금지 (UUID 또는 외부용 ID 분리)

## 시크릿 관리
- 코드/설정 파일에 시크릿 하드코딩 금지
- 환경 변수 또는 Vault 사용
- 에러 응답에 스택 트레이스/내부 정보 노출 금지
- 로그에 PII(개인정보), 토큰, 비밀번호 출력 금지
