# Hexagonal Architecture Rules

## 패키지 구조 (각 서비스)

```
com.flashsale.{service-name}/
├── adapter/
│   ├── in/web/        # Controller (WebFlux, suspend fun)
│   └── out/           # External adapters (Redis, Kafka, DB)
├── application/
│   ├── port/in/       # Use case interfaces
│   └── port/out/      # Output port interfaces
├── domain/            # Domain entities, value objects, errors
└── config/            # Spring configuration
```

## 의존성 방향

```
adapter/in → application/port/in → domain
adapter/out → application/port/out → domain
```

- **domain**: 외부 의존성 없음 (순수 Kotlin)
- **port**: 인터페이스만 (기술 세부사항 노출 금지)
- **adapter**: 구현체 (클래스명에 기술 스택 포함)

## 구현 순서 (신규 기능)

반드시 아래 순서를 따른다:
1. **Domain** — Entity, VO, sealed interface Error
2. **Port Out** — Output Port 인터페이스
3. **Port In** — UseCase 인터페이스
4. **UseCase** — 비즈니스 로직 (suspend fun, withTimeout)
5. **Adapter Out** — Redis/Kafka/DB 구현체
6. **Adapter In** — Controller (suspend fun, WebFlux)
7. **Config** — Spring 빈 등록
8. **Test** — 단위 → 통합 순서

## 네이밍 규칙

| 계층 | 패턴 | 예시 |
|------|------|------|
| Port In | `{동사}{명사}UseCase` | `PlaceOrderUseCase` |
| Port Out | `{명사}{동사}Port` | `StockDecrementPort` |
| Adapter | `{기술}{명사}Adapter` | `RedisStockAdapter` |
| Controller | `{명사}Controller` | `OrderController` |
| Error | `sealed interface {명사}Error` | `OrderError` |
