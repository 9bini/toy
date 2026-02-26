# Flash Sale Platform 기술 지식 베이스

> 이 프로젝트에서 사용하는 핵심 기술들을 이해하기 위한 학습 가이드입니다.
> 각 문서는 **개념 설명 → 이 프로젝트에서의 활용**  순서로 구성되어 있습니다.
>
> **바로 개발을 시작하려면**: [QUICK-START.md](QUICK-START.md) → [PROJECT-STRUCTURE.md](PROJECT-STRUCTURE.md) → [기능 추가 매뉴얼](#기능-추가-매뉴얼-docsguides)

---

## 전체 기술 스택 한눈에 보기

```
[클라이언트] → [Nginx] → [Gateway] → [Queue/Order/Payment/Notification Service]
                 │             │                │         │          │
                 │        Rate Limit       [Redis]    [Kafka]   [PostgreSQL]
                 │        (Token Bucket)     │          │          │
                 │                      Lua Script   이벤트     R2DBC
                 │                      분산 락      스트리밍   비동기 DB
                 │                      대기열
                 │
            [Prometheus] → [Grafana]
```

| 카테고리 | 기술 | 버전 | 역할 |
|---------|------|------|------|
| 언어 | Kotlin | 2.0.21 | 메인 언어 (JVM 21) |
| 비동기 | Kotlin Coroutines | 1.9.0 | 비동기/동시성 프로그래밍 |
| 웹 프레임워크 | Spring WebFlux | 3.4.1 | 리액티브 웹 서버 |
| 아키텍처 | Hexagonal Architecture | - | 포트 & 어댑터 패턴 |
| 캐시/큐/락 | Redis | 7.4 | 캐시, 대기열, 분산 락, Rate Limiting |
| 이벤트 | Apache Kafka | 3.8.1 | 서비스간 비동기 이벤트 통신 |
| DB | PostgreSQL + R2DBC | 16 / 1.0.7 | 비동기 관계형 데이터베이스 |
| DB 마이그레이션 | Flyway | 10.22.0 | 스키마 버전 관리 |
| 분산 트랜잭션 | Saga Pattern | - | 보상 트랜잭션으로 일관성 유지 |
| 리버스 프록시 | Nginx | 1.27 | Rate Limiting, 로드밸런싱, SSE 프록시 |
| 컨테이너 | Docker Compose | - | 로컬 개발 환경 인프라 |
| 모니터링 | Prometheus + Grafana | - | 메트릭 수집 + 시각화 |
| 테스트 | Kotest + MockK + Testcontainers | - | BDD 테스트, 모킹, 통합 테스트 |
| 빌드 | Gradle (Version Catalog) | - | 의존성 중앙 관리 |

---

## 권장 학습 순서

처음 접하는 기술이 많다면 아래 순서로 읽는 것을 추천합니다.

### Phase 1: 기초 (언어 & 프레임워크)
1. **[Kotlin Coroutines](01-kotlin-coroutines.md)** - 이 프로젝트의 모든 코드가 코루틴 기반
2. **[Spring WebFlux](02-spring-webflux.md)** - 왜 Spring MVC가 아닌 WebFlux인지

### Phase 2: 설계 (아키텍처)
3. **[Hexagonal Architecture](03-hexagonal-architecture.md)** - 코드 구조를 이해하는 핵심

### Phase 3: 핵심 인프라
4. **[Redis 활용](04-redis.md)** - 이 프로젝트에서 가장 많이 쓰이는 기술
5. **[Kafka 이벤트 스트리밍](05-kafka.md)** - 서비스간 통신의 핵심
6. **[R2DBC + PostgreSQL](06-r2dbc-postgresql.md)** - 비동기 DB 연결

### Phase 4: 안정성 & 패턴
7. **[Saga 패턴](08-saga-pattern.md)** - 분산 환경의 트랜잭션

### Phase 5: 운영 & 품질
9. **[테스트 전략](09-testing.md)** - Kotest, MockK, Testcontainers
10. **[인프라 구성](10-infra.md)** - Docker, Nginx, 모니터링

---

## 이 프로젝트가 풀려는 문제

**상황**: 10만 명이 동시에 접속하여 1,000개 한정 상품을 선착순으로 구매하려 함

**핵심 과제**:
- **초당 수만 건의 요청**을 처리해야 함 → WebFlux + Coroutines (논블로킹)
- **재고 정합성**: 1,000개만 정확히 판매 → Redis Lua Script (원자적 연산)
- **공정한 순서**: 먼저 온 사람이 먼저 구매 → Redis Sorted Set (대기열)
- **결제 실패 시 복구**: 재고 복원 필요 → Saga 패턴 (보상 트랜잭션)
- **서비스 장애 전파 방지**: 하나가 죽어도 전체는 유지 → withTimeout + 재시도 패턴
- **봇/매크로 차단**: 불공정 접근 방지 → Nginx + Redis Rate Limiting

---

## 실전 개발 가이드

프로젝트를 실행하고 코드를 작성하기 위한 필수 문서입니다.

| 문서 | 내용 |
|------|------|
| **[QUICK-START.md](QUICK-START.md)** | 5분 안에 프로젝트 실행하기 (인프라 → 빌드 → 서비스 실행 → 상태 확인) |
| **[PROJECT-STRUCTURE.md](PROJECT-STRUCTURE.md)** | 프로젝트 구조, 모듈 의존성, 네이밍 규칙, 공통 모듈 상세, 구현 순서 |

---

## 기능 추가 매뉴얼 (docs/guides/)

새로운 기능을 추가할 때 참고하는 단계별 가이드입니다. 모든 예제는 이 프로젝트의 실제 코드 패턴을 기반으로 합니다.

| 문서 | 주제 | 예제 |
|------|------|------|
| **[API 엔드포인트 추가](guides/add-api-endpoint.md)** | 새 API를 헥사고날 아키텍처로 구현 | order-service의 주문 생성 API |
| **[Kafka Consumer 추가](guides/add-kafka-consumer.md)** | 이벤트 수신 + 멱등성 + DLQ | payment-service의 주문 이벤트 수신 |
| **[Redis 연산 추가](guides/add-redis-operation.md)** | Lua Script, 분산 락, 대기열, Rate Limiting | 4가지 Redis 패턴 |
| **[DB 엔티티 추가](guides/add-db-entity.md)** | Flyway 마이그레이션 + R2DBC 엔티티 | order-service의 orders 테이블 |
| **[Saga 패턴 구현](guides/add-saga-pattern.md)** | 분산 트랜잭션 + 보상 트랜잭션 | 주문→재고→결제 플로우 |
| **[테스트 작성](guides/add-test.md)** | 단위/통합/E2E 테스트 | Kotest + MockK + Testcontainers |

---

## 인프라별 상세 문서 (docs/infra/)

각 인프라 기술을 처음부터 이해할 수 있는 상세 가이드입니다.

| 문서 | 기술 | 핵심 내용 |
|------|------|----------|
| [Docker Compose](infra/docker-compose.md) | Docker + Docker Compose | 컨테이너 개념, image/container, YAML 문법, healthcheck, overlay |
| [Nginx](infra/nginx.md) | Nginx 1.27 | 리버스 프록시, Rate Limiting(Leaky Bucket), 로드밸런싱, SSE 프록시 |
| [Redis](infra/redis.md) | Redis 7.4 | 자료구조 5종, 명령어, Lua Script, 영속성, Sentinel HA |
| [Kafka](infra/kafka.md) | Apache Kafka 3.8.1 | Topic/Partition/Offset/Consumer Group, KRaft, Replication, DLQ |
| [PostgreSQL](infra/postgresql.md) | PostgreSQL 16 | RDBMS 기초, SQL, 인덱스, 트랜잭션/ACID, WAL, 스트리밍 복제 |
| [Prometheus + Grafana](infra/prometheus-grafana.md) | Prometheus + Grafana | 메트릭 타입, Pull 모델, PromQL, 대시보드, 알림 |

## 라이브러리별 상세 문서 (docs/libs/)

각 라이브러리를 처음부터 이해할 수 있는 상세 가이드입니다.

| 문서 | 라이브러리 | 핵심 내용 |
|------|-----------|----------|
| [Kotlin Coroutines](libs/kotlin-coroutines.md) | kotlinx-coroutines 1.9.0 | suspend, 코루틴 빌더, 디스패처, Flow, 예외 처리 |
| [Spring WebFlux](libs/spring-webflux.md) | spring-boot-starter-webflux | Netty, Mono/Flux, Controller, WebClient, SSE |
| [Spring Data R2DBC](libs/spring-data-r2dbc.md) | spring-data-r2dbc | JDBC vs R2DBC, Repository, 코루틴 변환, 트랜잭션 |
| [Flyway](libs/flyway.md) | flyway-core 10.22.0 | DB 마이그레이션, 파일 규칙, R2DBC 환경 설정 |
| [Redisson](libs/redisson.md) | redisson 3.40.2 | 분산 락, RLock API, Watchdog, 재진입 |
| [Spring Kafka](libs/spring-kafka.md) | spring-kafka | KafkaTemplate, @KafkaListener, 에러 처리, DLQ |
| [Jackson](libs/jackson.md) | jackson-module-kotlin | JSON 직렬화/역직렬화, data class, 날짜 타입 |
| [kotlin-logging](libs/kotlin-logging.md) | kotlin-logging 7.0.3 + logstash | 로그 레벨, MDC, JSON 로그 |
| [ktlint](libs/ktlint.md) | ktlint 12.1.2 | 코드 스타일 검사/수정, Gradle 명령어 |
| [Micrometer + Actuator](libs/micrometer-actuator.md) | Micrometer + Actuator | 메트릭 API, Counter/Timer/Gauge, Prometheus 연동 |
| [Kotest + MockK](libs/kotest-mockk.md) | Kotest 5.9.1 + MockK 1.13.13 | 테스트 스타일, Assertion, 모킹, Testcontainers |

---

## 용어 사전 (빠른 참조)

| 용어 | 설명 |
|------|------|
| **코루틴 (Coroutine)** | 일시 정지 가능한 경량 스레드. `suspend fun`으로 선언 |
| **리액티브 (Reactive)** | 논블로킹 방식으로 데이터 스트림을 처리하는 프로그래밍 패러다임 |
| **포트 (Port)** | 비즈니스 로직과 외부 세계의 경계를 정의하는 인터페이스 |
| **어댑터 (Adapter)** | 포트 인터페이스를 실제 기술로 구현한 클래스 |
| **Lua Script** | Redis 서버에서 원자적으로 실행되는 스크립트 |
| **분산 락 (Distributed Lock)** | 여러 서버가 공유 자원에 동시 접근하지 못하게 하는 메커니즘 |
| **Saga** | 분산 시스템에서 여러 서비스에 걸친 트랜잭션을 관리하는 패턴 |
| **서킷 브레이커 (Circuit Breaker)** | 장애가 전파되지 않도록 호출을 차단하는 패턴 |
| **SSE (Server-Sent Events)** | 서버가 클라이언트에 실시간으로 이벤트를 보내는 단방향 통신 |
| **멱등성 (Idempotency)** | 같은 요청을 여러 번 보내도 결과가 동일한 성질 |
| **KRaft** | Kafka가 ZooKeeper 없이 자체 메타데이터를 관리하는 모드 |
| **R2DBC** | Reactive Relational Database Connectivity. 비동기 DB 드라이버 |
| **Flyway** | SQL 기반 DB 스키마 버전 관리 도구 |
| **DLQ (Dead Letter Queue)** | 처리 실패한 메시지를 보관하는 별도의 큐 |
