# Flash Sale Platform (실시간 선착순 한정판매 시스템)

## 프로젝트 개요
10만 동시 접속, 1,000개 한정 상품 선착순 구매 시스템.
Kotlin + Spring WebFlux + Coroutines 기반 마이크로서비스 아키텍처.

## 아키텍처
- **gateway**: API Gateway + Rate Limiting (Redis Token Bucket)
- **queue-service**: 대기열 관리 (Redis Sorted Set + SSE)
- **order-service**: 주문 처리 (Redis Lua Script + Redisson 분산 락)
- **payment-service**: 결제 + Saga 패턴 보상 트랜잭션
- **notification-service**: 알림 (SSE + Push + 외부 API)
- **common/domain**: 공유 도메인 모델
- **common/infrastructure**: 공유 인프라 (Redis, Kafka 설정)

## 빌드 & 실행
- 전체 빌드: `./gradlew build`
- 특정 서비스: `./gradlew :services:order-service:build`
- 전체 테스트: `./gradlew test`
- 특정 테스트: `./gradlew :services:order-service:test --tests "*.OrderServiceTest"`
- 인프라 실행: `docker compose up -d`
- 인프라 종료: `docker compose down`
- 린트 체크: `./gradlew ktlintCheck`
- 린트 포맷: `./gradlew ktlintFormat`

## 코드 컨벤션
- Kotlin 코드 스타일: ktlint (공식 Kotlin 스타일 가이드)
- 모든 I/O 함수는 `suspend fun` 사용, blocking 코드 금지
- `coroutineScope` / `supervisorScope` 적절히 사용
- Redis 연산은 반드시 Lua Script 또는 Redisson으로 원자성 보장
- Kafka 메시지는 반드시 멱등성 처리
- 모든 외부 통신은 `withTimeout` 설정 필수
- sealed class / sealed interface로 에러 타입 정의
- 문서와 주석은 한국어, 코드(변수명/클래스명/함수명)는 영어

## 패키지 구조 (각 서비스)
```
com.flashsale.{service-name}/
├── adapter/
│   ├── in/web/        # Controller (WebFlux)
│   └── out/           # External adapters (Redis, Kafka, DB)
├── application/
│   ├── port/in/       # Use case interfaces
│   └── port/out/      # Output port interfaces
├── domain/            # Domain entities, value objects
└── config/            # Spring configuration
```

## Git 워크플로우
- 커밋 메시지: conventional commits (feat:, fix:, refactor:, test:, docs:, perf:)
- 한국어 커밋 메시지 허용 (예: `feat: 주문 서비스 재고 차감 로직 구현`)
- 브랜치: feature/{service-name}/{feature-description}

## IMPORTANT
- 테스트 작성 후 반드시 실행하여 통과 확인
- Redis/Kafka 연동 코드는 반드시 통합 테스트 작성 (Testcontainers 사용)
- 성능에 영향을 주는 변경은 벤치마크 실행 권장
- docker compose 인프라가 실행 중인지 확인 후 통합 테스트 수행

---

## 코드 가독성 규칙 (이 프로젝트 전용)

### Hexagonal Architecture 가독성
- Port 인터페이스는 "무엇을 하는가"만 표현 (구현 세부사항 노출 금지)
- Adapter 클래스명에 기술 스택 포함: `RedisStockAdapter`, `KafkaOrderEventPublisher`
- UseCase 클래스명은 비즈니스 동작: `PlaceOrderUseCase`, `DecrementStockUseCase`

### 코루틴 가독성
- `coroutineScope` 사용 시 주석으로 "왜 병렬화하는지" 설명
- `withTimeout` 값은 상수로 정의하고 이름에 의도 포함:
  ```kotlin
  companion object {
      // Redis 응답은 보통 1-5ms, 100ms 넘으면 문제 상황
      private val REDIS_OPERATION_TIMEOUT = 100.milliseconds
      // 외부 결제 API는 최대 3초까지 허용
      private val PAYMENT_API_TIMEOUT = 3.seconds
  }
  ```

### Redis/Kafka 가독성
- Redis 키 패턴은 `object RedisKeys`에 상수로 집중 관리:
  ```kotlin
  object RedisKeys {
      fun stock(productId: String) = "stock:product:$productId"
      fun queue(saleEventId: String) = "queue:sale:$saleEventId"
  }
  ```
- Kafka 토픽명은 상수 파일에 집중 관리

### Sealed Class 에러 정의
- 각 에러 타입에 KDoc으로 "언제 이 에러가 발생하는지" 설명:
  ```kotlin
  sealed interface OrderError {
      /** 상품 재고가 요청 수량보다 부족할 때 */
      data class InsufficientStock(val available: Int, val requested: Int) : OrderError
      /** 결제 게이트웨이 타임아웃 (3초 초과) */
      data class PaymentTimeout(val orderId: String) : OrderError
  }
  ```

---

## 반복 이슈 기록

> 발견된 반복 이슈를 누적 기록. 새 이슈 발견 시 여기에 추가를 제안한다.

| 날짜 | 이슈 | 원인 | 해결 패턴 |
|------|------|------|-----------|

---

## 구현 순서 가이드 (신규 기능 구현 시 필수)
아래 순서를 반드시 따릅니다:
1. **Domain** - Entity, Value Object, sealed interface Error (외부 의존성 없음)
2. **Port Out** - Output Port 인터페이스 (기술 세부사항 노출 금지)
3. **Port In** - UseCase 인터페이스
4. **UseCase** - 비즈니스 로직 구현 (suspend fun, withTimeout)
5. **Adapter Out** - Redis/Kafka/DB 구현체 (클래스명에 기술 스택 포함)
6. **Adapter In** - Controller (suspend fun, WebFlux)
7. **Config** - Spring 빈 등록
8. **Test** - 단위 테스트 → 통합 테스트 순서

## 스킬 사용 가이드
| 작업 | 스킬 | 설명 |
|------|------|------|
| 기능 전체 구현 | `/full-feature` | 설계→구현→테스트→PR 원스톱 |
| 빠른 버그 수정 | `/hotfix` | 분석→수정→테스트→PR |
| API 엔드포인트 | `/implement-api` | 단일 API 구현 |
| 테스트 작성 | `/write-test` | 특정 클래스 테스트 |
| 서비스 설계 | `/design-service` | DDD 기반 설계 |
| 코드 리뷰 | `/review-code` | 코드 품질 검사 |
| 디버깅 | `/debug-issue` | 체계적 디버깅 |
| Redis 설정 | `/redis-setup` | Lua Script, 분산 락, 대기열 |
| Kafka 설정 | `/kafka-setup` | Producer/Consumer 설정 |
| Saga 구현 | `/saga-pattern` | 분산 트랜잭션 |
| 전체 검사 | `/check-all` | 빌드+테스트+린트+아키텍처 |
| 문서 작성 | `/document` | ADR, API 문서 |

## 자가 리뷰 체크리스트 (코드 제출 전 필수)

### 기능
- [ ] 요구사항을 모두 구현했는가?
- [ ] 엣지 케이스 (null, 빈 값, 동시성)를 처리했는가?

### 아키텍처
- [ ] Hexagonal Architecture 패키지 구조를 따르는가?
- [ ] 의존성 방향이 domain을 향하는가?

### 코루틴 안전성
- [ ] 모든 I/O가 suspend fun인가?
- [ ] GlobalScope를 사용하지 않았는가?
- [ ] withTimeout이 외부 호출에 설정되었는가?

### 동시성/정합성
- [ ] Redis 연산이 원자적인가?
- [ ] Kafka 메시지가 멱등하게 처리되는가?

### 가독성
- [ ] 함수가 30줄 이내인가?
- [ ] 함수명/변수명이 의도를 명확히 표현하는가?
- [ ] 복잡한 비즈니스 로직에 한국어 주석이 있는가?
