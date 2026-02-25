---
name: implement-api
description: Spring WebFlux + Kotlin Coroutines 기반 API 엔드포인트를 구현합니다. Controller, UseCase, Port, Adapter 전체 레이어를 생성합니다.
argument-hint: [service-name] [endpoint-description]
---

$ARGUMENTS API를 구현하세요.

## 구현 순서

### 1. 설계 문서 확인
- `docs/{service-name}/DESIGN.md`가 있으면 API 스펙 확인
- 없으면 사용자에게 확인 후, 필요 시 `/design-service`로 설계 진행

### 2. Domain 레이어
- 엔티티, 밸류 오브젝트 생성
- 도메인 서비스 (필요시)

### 3. Application 레이어
- Output Port 인터페이스 정의 (Port Out)
- Input Port (UseCase) 인터페이스 정의
- UseCase 구현 클래스 작성
  - 반드시 `suspend fun` 사용
  - 병렬 가능한 작업은 `coroutineScope { async { } }` 활용

### 4. Adapter 레이어
- Output Adapter 구현 (Redis/Kafka/DB)
- Input Adapter (Controller) 구현
  - `@RestController` + `suspend fun` 방식
  - 입력 검증은 Controller에서 처리

### 5. 설정
- Spring Configuration 클래스
- application.yml 설정

### 6. 테스트
- UseCase 단위 테스트 (MockK)
- Controller 테스트 (WebTestClient)
- 작성 후 반드시 `./gradlew :services:{service}:test` 실행

## 코드 패턴

```kotlin
// UseCase 구현 예시
@Service
class PlaceOrderUseCase(
    private val stockPort: StockPort,
    private val orderPort: OrderPort,
) : PlaceOrderPort {

    override suspend fun execute(command: PlaceOrderCommand): OrderResult =
        coroutineScope {
            val stock = async { stockPort.verify(command.productId) }
            val user = async { userPort.validate(command.userId) }
            awaitAll(stock, user)
            orderPort.save(Order.create(command))
        }
}
```

## 필수 원칙
- 모든 I/O 함수는 `suspend fun`
- 에러는 `sealed class Result<T>` 또는 `sealed interface` 로 처리
- Request/Response DTO는 별도 파일 분리
- 입력 검증은 Controller 레이어에서
