# RedTeam 자동 보안 리뷰

코드 변경 시 아래 10개 카테고리로 자동 보안 리뷰를 수행한다.
위험도: CRITICAL > HIGH > MEDIUM > LOW

## 점검 카테고리

### RT-01: Race Condition (CRITICAL)
- 재고 차감이 atomic하지 않은 경우
- 분산 환경에서 동시 요청 시 정합성 깨짐
- 검증: Redis Lua Script 또는 Redisson 분산 락 사용 확인

### RT-02: Input Injection (HIGH)
- Redis 키에 사용자 입력 직접 삽입
- SQL/NoSQL Injection
- Command Injection (외부 프로세스 실행 시)
- 검증: 입력 sanitization, parameterized query 확인

### RT-03: Authentication Bypass (CRITICAL)
- 인증 없이 접근 가능한 보호 엔드포인트
- 토큰 검증 누락, 만료 체크 누락
- 검증: SecurityFilterChain 설정 확인

### RT-04: IDOR (HIGH)
- 리소스 접근 시 소유권 미확인
- 순차 ID 노출로 다른 사용자 데이터 접근 가능
- 검증: 모든 리소스 조회에 소유자 검증 로직 확인

### RT-05: Rate Limiting Bypass (HIGH)
- Rate Limiter 적용 누락
- 우회 가능한 식별자 (IP만 사용, 토큰 미사용)
- 검증: Nginx + Application 이중 방어 확인

### RT-06: Secret Exposure (CRITICAL)
- 코드/설정에 하드코딩된 시크릿
- 에러 응답에 스택 트레이스 노출
- 로그에 PII/토큰 출력
- 검증: grep으로 패턴 스캔 (password, secret, token, key)

### RT-07: Timeout Missing (MEDIUM)
- 외부 호출에 withTimeout 누락
- 무한 대기 가능한 I/O 연산
- 검증: 모든 외부 호출에 timeout 상수 확인

### RT-08: Idempotency Gap (HIGH)
- Kafka Consumer에 멱등성 처리 누락
- 재시도 시 중복 실행되는 부작용
- 검증: 멱등성 키 + 중복 체크 로직 확인

### RT-09: Error Information Leak (MEDIUM)
- 내부 예외 메시지가 클라이언트에 노출
- DB 스키마, 내부 경로 등 시스템 정보 유출
- 검증: GlobalExceptionHandler에서 예외 변환 확인

### RT-10: Coroutine Safety (HIGH)
- GlobalScope 사용 (구조화된 동시성 위반)
- blocking 호출이 suspend fun 내에 존재
- 부적절한 Dispatcher 사용
- 검증: Dispatchers.IO 격리, structured concurrency 확인

## 리뷰 출력 형식

```
[RT-{번호}] {카테고리} | {위험도}
파일: {파일 경로}:{라인}
문제: {설명}
공격: {공격 시나리오}
수정: {수정 방법 또는 코드}
```

## 오탐 억제
`redteam/suppressions.json`에 등록된 항목은 리뷰에서 제외한다.
