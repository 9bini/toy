# 5분 안에 프로젝트 실행하기

---

## 목차

1. [사전 요구사항](#1-사전-요구사항)
2. [인프라 실행](#2-인프라-실행)
3. [프로젝트 빌드](#3-프로젝트-빌드)
4. [서비스 실행](#4-서비스-실행)
5. [상태 확인](#5-상태-확인)
6. [자주 쓰는 명령어](#6-자주-쓰는-명령어)
7. [트러블슈팅](#7-트러블슈팅)

---

## 1. 사전 요구사항

| 도구 | 버전 | 확인 명령어 |
|------|------|------------|
| **JDK** | 21 이상 | `java -version` |
| **Docker Desktop** | 최신 | `docker --version` |
| **Docker Compose** | v2 이상 | `docker compose version` |

> Gradle은 프로젝트에 포함된 `gradlew` (Gradle Wrapper)를 사용하므로 별도 설치 불필요.

---

## 2. 인프라 실행

프로젝트 루트에서 Docker Compose로 인프라를 띄웁니다.

```bash
# 프로젝트 루트로 이동
cd flash-sale-platform

# 인프라 컨테이너 실행 (백그라운드)
docker compose up -d
```

### 실행되는 컨테이너

| 컨테이너 | 포트 | 역할 |
|----------|------|------|
| `flashsale-nginx` | 80 | 리버스 프록시, Rate Limiting |
| `flashsale-redis` | 6379 | 캐시, 대기열, 분산 락 |
| `flashsale-kafka` | 9092 | 이벤트 스트리밍 |
| `flashsale-postgres` | 5432 | 주문/결제 데이터 |
| `flashsale-prometheus` | 9090 | 메트릭 수집 |
| `flashsale-grafana` | 3000 | 모니터링 대시보드 |

### 실행 확인

```bash
docker compose ps
```

모든 컨테이너가 `running (healthy)` 상태인지 확인합니다.

> Kafka는 healthcheck `start_period`가 30초이므로 처음 시작 시 약간 기다려야 합니다.

---

## 3. 프로젝트 빌드

```bash
# 전체 빌드
./gradlew build

# 특정 서비스만 빌드
./gradlew :services:order-service:build
```

### 빌드에 포함되는 작업

```
./gradlew build
├── compileKotlin     (컴파일)
├── ktlintCheck       (코드 스타일 검사)
└── test              (테스트 실행)
```

---

## 4. 서비스 실행

각 서비스를 IDE에서 실행하거나 Gradle로 직접 실행합니다.

### 서비스 포트 목록

| 서비스 | 포트 | Gradle 명령어 |
|--------|------|-------------|
| **gateway** | 8080 | `./gradlew :services:gateway:bootRun` |
| **queue-service** | 8081 | `./gradlew :services:queue-service:bootRun` |
| **order-service** | 8082 | `./gradlew :services:order-service:bootRun` |
| **payment-service** | 8083 | `./gradlew :services:payment-service:bootRun` |
| **notification-service** | 8084 | `./gradlew :services:notification-service:bootRun` |

### IntelliJ에서 실행

각 서비스의 `*Application.kt` 파일을 열고 `main()` 함수 옆의 ▶ 버튼을 클릭합니다.

```
services/gateway/src/main/kotlin/.../GatewayApplication.kt
services/queue-service/src/main/kotlin/.../QueueServiceApplication.kt
services/order-service/src/main/kotlin/.../OrderServiceApplication.kt
services/payment-service/src/main/kotlin/.../PaymentServiceApplication.kt
services/notification-service/src/main/kotlin/.../NotificationServiceApplication.kt
```

---

## 5. 상태 확인

### 인프라 상태

```bash
# Docker 컨테이너 상태
docker compose ps

# Redis 연결 확인
docker exec flashsale-redis redis-cli ping
# → PONG

# PostgreSQL 연결 확인
docker exec flashsale-postgres pg_isready -U flashsale
# → accepting connections

# Kafka 브로커 확인
docker exec flashsale-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

### 서비스 상태

```bash
# 각 서비스의 health 엔드포인트
curl http://localhost:8080/actuator/health   # gateway
curl http://localhost:8081/actuator/health   # queue-service
curl http://localhost:8082/actuator/health   # order-service
curl http://localhost:8083/actuator/health   # payment-service
curl http://localhost:8084/actuator/health   # notification-service
```

응답 예시:
```json
{"status": "UP"}
```

### 모니터링 대시보드

| 서비스 | URL | 계정 |
|--------|-----|------|
| Prometheus | http://localhost:9090 | - |
| Grafana | http://localhost:3000 | admin / admin |

---

## 6. 자주 쓰는 명령어

### 빌드

```bash
./gradlew build                           # 전체 빌드
./gradlew :services:order-service:build   # 특정 서비스 빌드
./gradlew build -x test                   # 테스트 제외 빌드
```

### 테스트

```bash
./gradlew test                            # 전체 테스트
./gradlew :services:order-service:test    # 특정 서비스 테스트
./gradlew :services:order-service:test --tests "*.OrderServiceTest"  # 특정 클래스
```

### 코드 스타일

```bash
./gradlew ktlintCheck                     # 스타일 검사
./gradlew ktlintFormat                    # 자동 수정
```

### 인프라

```bash
docker compose up -d                      # 인프라 시작
docker compose down                       # 인프라 종료
docker compose down -v                    # 인프라 종료 + 볼륨 삭제 (데이터 초기화)
docker compose logs -f redis              # 특정 컨테이너 로그
docker compose restart kafka              # 특정 컨테이너 재시작
```

### HA 모드 (고가용성)

```bash
# Redis Sentinel + Kafka 3브로커 구성
docker compose -f docker-compose.yml -f docker-compose.ha.yml up -d
```

---

## 7. 트러블슈팅

### 포트 충돌

```
Error: Bind for 0.0.0.0:6379 failed: port is already allocated
```

**원인**: 호스트에 이미 Redis/Kafka/PostgreSQL이 실행 중

**해결**:
```bash
# 해당 포트를 사용하는 프로세스 확인
lsof -i :6379
# 또는 기존 Docker 컨테이너 확인
docker ps | grep 6379
# 기존 프로세스 중지 후 다시 실행
docker compose up -d
```

### Docker 미실행

```
Cannot connect to the Docker daemon
```

**해결**: Docker Desktop을 실행합니다.

### 메모리 부족 (Kafka 시작 실패)

```
kafka  | java.lang.OutOfMemoryError: Java heap space
```

**해결**: Docker Desktop의 Settings → Resources → Memory를 **4GB 이상**으로 설정합니다.

### Redis 연결 실패

```
Unable to connect to Redis; nested exception is io.lettuce.core.RedisConnectionException
```

**해결**:
```bash
# Redis 컨테이너 상태 확인
docker compose ps redis
# 재시작
docker compose restart redis
```

### Kafka 아직 준비 안 됨

```
org.apache.kafka.common.errors.TimeoutException
```

**해결**: Kafka healthcheck가 완료될 때까지 기다립니다 (최대 30초).
```bash
# Kafka 상태 확인
docker compose ps kafka
# 로그 확인
docker compose logs -f kafka
```

### Flyway 마이그레이션 실패

```
FlywayException: Validate failed: Detected applied migration not resolved locally
```

**해결**: 개발 환경에서는 DB를 초기화합니다.
```bash
docker compose down -v  # 볼륨 삭제
docker compose up -d    # 다시 시작
```

### Gradle 빌드 실패 (JDK 버전)

```
Unsupported class file major version 65
```

**해결**: JDK 21을 사용하는지 확인합니다.
```bash
java -version
# openjdk version "21.x.x" 이어야 함
```

IntelliJ: File → Project Structure → Project SDK → 21 선택
