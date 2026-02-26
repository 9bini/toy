# 10. 인프라 구성 (Docker, Nginx, 모니터링)

> **한 줄 요약**: Docker Compose로 로컬 인프라를 구성하고, Nginx로 트래픽을 제어하며, Prometheus+Grafana로 모니터링

---

## 전체 인프라 구성도

```
                        [사용자 브라우저]
                              │
                              ▼
                     ┌─── [Nginx:80] ───┐
                     │   Rate Limiting   │
                     │   Load Balancing  │
                     │   SSE Proxy       │
                     └────────┬──────────┘
                              │
            ┌─────────┬───────┼────────┬──────────┐
            ▼         ▼       ▼        ▼          ▼
       [Gateway]  [Queue]  [Order] [Payment] [Notification]
        :8080     :8081    :8082    :8083      :8084
            │         │       │        │
            └────┬────┘       └───┬────┘
                 ▼                ▼
          [Redis:6379]    [PostgreSQL:5432]
                 │
          [Kafka:9092]

      [Prometheus:9090] ←── 메트릭 수집 ──── [각 서비스 /actuator/prometheus]
              │
              ▼
      [Grafana:3000] ←── 대시보드 시각화
```

---

## 1. Docker Compose

### Docker Compose란?

여러 Docker 컨테이너를 **한 번에 정의하고 실행**하는 도구입니다.
`docker-compose.yml` 파일 하나로 Redis, Kafka, PostgreSQL 등을 모두 관리합니다.

### 이 프로젝트의 Docker Compose 구성

```yaml
# docker-compose.yml (개발 환경)
services:
  nginx:         # L7 리버스 프록시 + Rate Limiting
  redis:         # 캐시, 분산 락, 대기열
  kafka:         # 이벤트 스트리밍 (KRaft 모드)
  postgres:      # 영속 데이터
  prometheus:    # 메트릭 수집
  grafana:       # 모니터링 대시보드
```

### 자주 쓰는 명령어

```bash
# 인프라 시작
docker compose up -d

# 인프라 종료
docker compose down

# 특정 서비스만 시작
docker compose up -d redis kafka

# 로그 확인
docker compose logs -f kafka

# 컨테이너 상태 확인
docker compose ps

# HA(고가용성) 모드로 시작
docker compose -f docker-compose.yml -f docker-compose.ha.yml up -d
```

### Healthcheck (상태 확인)

각 서비스는 healthcheck를 설정하여, 서비스가 준비될 때까지 기다립니다.

```yaml
redis:
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]    # PONG 응답하면 정상
    interval: 10s
    timeout: 5s
    retries: 5

kafka:
  healthcheck:
    test: ["CMD-SHELL", "kafka-broker-api-versions.sh --bootstrap-server localhost:9092"]
    interval: 15s
    timeout: 10s
    retries: 10
    start_period: 30s    # 시작 후 30초는 실패해도 무시 (초기화 시간)

postgres:
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U flashsale"]
    interval: 10s
    timeout: 5s
    retries: 5
```

### depends_on (의존 순서)

```yaml
nginx:
  depends_on:
    redis:
      condition: service_healthy    # Redis가 healthy가 된 후에 시작
    kafka:
      condition: service_healthy    # Kafka가 healthy가 된 후에 시작
```

---

## 2. Nginx (리버스 프록시)

### 역할

```
[사용자] ─── 요청 ──→ [Nginx] ─── 프록시 ──→ [서비스]
                        │
              1. Rate Limiting (과도한 요청 차단)
              2. Load Balancing (여러 인스턴스에 분배)
              3. SSE Proxy (장시간 연결 유지)
```

### Rate Limiting (요청 제한)

```nginx
# IP당 초당 요청 수 제한
limit_req_zone $binary_remote_addr zone=api_rate:10m rate=50r/s;
# 주문 API는 더 엄격 (선착순 공정성)
limit_req_zone $binary_remote_addr zone=order_rate:10m rate=5r/s;

# 일반 API: 초당 50건, 순간 100건까지 허용
location /api/ {
    limit_req zone=api_rate burst=100 nodelay;
}

# 주문 API: 초당 5건, 순간 10건까지 허용
location /api/orders {
    limit_req zone=order_rate burst=10 nodelay;
}
```

| 파라미터 | 의미 |
|---------|------|
| `rate=50r/s` | 초당 50건 허용 |
| `burst=100` | 순간적으로 100건까지 초과 허용 (버퍼) |
| `nodelay` | burst 범위 내 요청은 지연 없이 즉시 처리 |

### 2중 Rate Limiting

```
[사용자] → [Nginx Rate Limit] → [Gateway Rate Limit (Redis)] → [서비스]
            1차 방어선              2차 방어선
            IP 기반                 사용자/API 키 기반
            단순하고 빠름            세밀한 제어 가능
```

- **Nginx**: IP 기반, 빠르고 단순한 1차 차단 (봇/매크로)
- **Gateway**: Redis Token Bucket 기반, 사용자별 세밀한 제어

### SSE 프록시 설정

```nginx
location /api/queue/ {
    proxy_read_timeout 300s;    # 5분 유지 (대기열 최대 대기 시간)
    proxy_buffering off;        # 실시간 이벤트 즉시 전달
    proxy_cache off;            # 캐시 비활성화
    chunked_transfer_encoding on;
}
```

SSE(Server-Sent Events)는 서버가 클라이언트에 지속적으로 이벤트를 보내는 기술입니다.
일반 HTTP와 다르게 **연결이 끊기지 않고 유지**되므로, 특별한 설정이 필요합니다.

### Load Balancing (로드밸런싱)

```nginx
upstream queue_service {
    least_conn;                        # 연결 수가 가장 적은 서버로
    server host.docker.internal:8081;  # 개발: 1대
    # 운영:
    # server queue-1:8081;
    # server queue-2:8081;
    # server queue-3:8081;
    keepalive 64;                      # 연결 재사용
}
```

---

## 3. 모니터링 (Prometheus + Grafana)

### Prometheus란?

서비스에서 **메트릭(숫자 데이터)**을 수집하는 시계열 데이터베이스입니다.

```
작동 방식:
[주문 서비스 /actuator/prometheus] ←── Prometheus가 주기적으로 가져감 (Pull)
[결제 서비스 /actuator/prometheus] ←── (15초마다)
[Gateway /actuator/prometheus]    ←──
```

### 수집되는 메트릭 예시

```
# HTTP 요청 수
http_server_requests_seconds_count{uri="/api/orders", status="200"} 15234
http_server_requests_seconds_count{uri="/api/orders", status="429"} 892

# HTTP 응답 시간
http_server_requests_seconds_sum{uri="/api/orders"} 456.78

# JVM 메모리
jvm_memory_used_bytes{area="heap"} 268435456

# Redis 연결 수
lettuce_command_completion_seconds_count 98765
```

### Grafana란?

Prometheus의 메트릭을 **시각적 대시보드**로 보여주는 도구입니다.

```
접속: http://localhost:3000
기본 계정: admin / admin
```

그래프, 게이지, 테이블 등으로 시스템 상태를 실시간 모니터링합니다.

### Spring Actuator + Micrometer

각 서비스에서 Prometheus 메트릭을 노출하는 것은 Spring Actuator와 Micrometer가 담당합니다.

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
}
```

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus, info
  metrics:
    export:
      prometheus:
        enabled: true
```

---

## 4. Gradle Version Catalog

### 왜 필요한가?

멀티 모듈 프로젝트에서 **라이브러리 버전을 한 곳에서 관리**합니다.

### 구조

```
gradle/libs.versions.toml          ← 버전 정의 파일 (단 하나)
├── [versions]: 버전 번호
├── [libraries]: 라이브러리 좌표
├── [bundles]: 라이브러리 묶음
└── [plugins]: 플러그인

services/order-service/build.gradle.kts   ← 사용하는 곳
```

### 사용 방법

```toml
# gradle/libs.versions.toml
[versions]
coroutines = "1.9.0"
kotest = "5.9.1"

[libraries]
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotest-runner-junit5 = { module = "io.kotest:kotest-runner-junit5", version.ref = "kotest" }

[bundles]
coroutines = ["coroutines-core", "coroutines-reactor", "coroutines-slf4j"]
kotest = ["kotest-runner-junit5", "kotest-assertions-core", "kotest-property"]
```

```kotlin
// build.gradle.kts에서 사용
dependencies {
    implementation(libs.coroutines.core)        // 개별 라이브러리
    implementation(libs.bundles.coroutines)     // 번들 (여러 개 한번에)
    testImplementation(libs.bundles.kotest)
}
```

**장점**: 코루틴 버전을 올리려면 `libs.versions.toml`에서 한 줄만 수정하면 됨.

---

## 개발 환경 vs 운영 환경

| 구분 | 개발 (docker-compose.yml) | 운영 (+ docker-compose.ha.yml) |
|------|--------------------------|-------------------------------|
| Redis | 단일 인스턴스 | Primary + Replica + Sentinel 3노드 |
| Kafka | 1 브로커, replication=1 | 3 브로커, replication=3, min.isr=2 |
| PostgreSQL | 단일 인스턴스 | Primary + Read Replica |
| Nginx | 단일 서버 | (운영에서는 upstream에 여러 서버 추가) |
| 메모리 | Redis 256MB | Redis 1GB |

```bash
# 개발 환경
docker compose up -d

# 운영(HA) 환경
docker compose -f docker-compose.yml -f docker-compose.ha.yml up -d
```

---

## 포트 정리

| 서비스 | 포트 | 용도 |
|--------|------|------|
| Nginx | 80 | 외부 접점 (리버스 프록시) |
| Gateway | 8080 | API Gateway |
| Queue Service | 8081 | 대기열 관리 |
| Order Service | 8082 | 주문 처리 |
| Payment Service | 8083 | 결제 처리 |
| Notification Service | 8084 | 알림 서비스 |
| Redis | 6379 | 인메모리 저장소 |
| Kafka | 9092 | 이벤트 스트리밍 |
| PostgreSQL | 5432 | 관계형 데이터베이스 |
| Prometheus | 9090 | 메트릭 수집 |
| Grafana | 3000 | 모니터링 대시보드 |

---

## 더 알아보기

- **Docker Compose 공식**: [docs.docker.com/compose](https://docs.docker.com/compose/)
- **Nginx 공식**: [nginx.org](https://nginx.org/)
- **Prometheus**: [prometheus.io](https://prometheus.io/)
- **Grafana**: [grafana.com](https://grafana.com/)
- **이 프로젝트 파일**: `docker-compose.yml`, `docker-compose.ha.yml`, `infra/nginx/nginx.conf`
