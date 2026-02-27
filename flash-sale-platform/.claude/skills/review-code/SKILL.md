---
name: review-code
description: 코드 리뷰를 수행합니다. 동시성 안전성, 성능, 코루틴 패턴, Redis/Kafka 사용 패턴을 중점 검토합니다.
argument-hint: [file-path-or-service-name]
context: fork
---

$ARGUMENTS 코드를 리뷰하세요.

## 리뷰 체크리스트

### 동시성 안전성
- [ ] 공유 상태 없이 코루틴 안전한가?
- [ ] Redis 연산의 원자성이 보장되는가? (Lua Script / Redisson)
- [ ] 분산 락 사용이 적절한가? (데드락 위험 없는가?)
- [ ] Race condition 가능성이 없는가?

### 성능
- [ ] 불필요한 blocking 호출이 없는가? (JDBC, Thread.sleep 등)
- [ ] 병렬 가능한 작업이 순차 실행되고 있지 않은가?
- [ ] N+1 쿼리 패턴이 없는가?
- [ ] 불필요한 메모리 할당이 없는가?

### 에러 처리 / Resilience
- [ ] `withTimeout`이 외부 호출에 설정되었는가?
- [ ] 네트워크 호출(HTTP, Kafka)에 `@Retryable`이 적용되었는가?
- [ ] `@Retryable`의 `includes`/`excludes`로 비즈니스 에러를 제외했는가?
- [ ] 실패 시 보상 처리가 구현되었는가?
- [ ] DLQ로 격리되는가?
- [ ] 에러 로깅이 적절한가?

### Kotlin / Coroutines
- [ ] Structured concurrency가 지켜지는가? (GlobalScope 금지)
- [ ] `SupervisorScope` 사용이 적절한가?
- [ ] Context switching이 최소화되었는가?
- [ ] `Dispatchers.IO`가 blocking 코드에만 사용되는가?
- [ ] Flow 수집이 적절한 스코프에서 이루어지는가?

### Kafka
- [ ] 메시지 발행이 멱등한가?
- [ ] Consumer가 중복 메시지를 안전하게 처리하는가?
- [ ] DLQ 설정이 되어있는가?

### Redis
- [ ] 키 네이밍이 일관적인가?
- [ ] TTL이 설정되어 있는가?
- [ ] 메모리 누수 위험이 없는가?

### 기술 최신성
- [ ] deprecated API를 사용하지 않는가? (Spring Boot/Kotlin/라이브러리 최신 버전 기준)
- [ ] 최신 안정 버전에서 제공하는 더 나은 대안이 있는가?

## 출력 형식
결과를 우선순위별로 분류:
- **Critical** (반드시 수정): 데이터 정합성, 보안, 동시성 버그
- **Warning** (권장 수정): 성능 이슈, 잠재적 문제
- **Suggestion** (개선 제안): 코드 가독성, 패턴 개선

각 항목에 구체적 코드 라인 참조와 수정 예시를 포함하세요.
