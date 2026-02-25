# Docker & Docker Compose

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [핵심 개념](#3-핵심-개념)
4. [Docker Compose YAML 문법](#4-docker-compose-yaml-문법)
5. [이 프로젝트에서의 활용](#5-이-프로젝트에서의-활용)
6. [명령어 레퍼런스](#6-명령어-레퍼런스)
7. [자주 하는 실수 / 주의사항](#7-자주-하는-실수--주의사항)
8. [정리 / 한눈에 보기](#8-정리--한눈에-보기)
9. [더 알아보기](#9-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

**Docker**는 애플리케이션을 격리된 환경(컨테이너)에서 실행하는 도구이고,
**Docker Compose**는 여러 컨테이너를 YAML 파일 하나로 정의하고 한 번에 실행하는 도구다.

### 비유: 포장이사

- **Docker 없이 개발**: 새 컴퓨터에 올 때마다 Redis 설치, Kafka 설치, PostgreSQL 설치, 버전 맞추기... 반나절
- **Docker**: 이사할 때 짐을 **컨테이너 박스**에 넣어서 통째로 옮기는 것. Redis든 Kafka든 컨테이너 하나로 "그냥 실행"
- **Docker Compose**: 이사 업체의 **작업 지시서**. "이 박스는 거실에, 저 박스는 주방에" 처럼 여러 컨테이너를 한 번에 배치

### 핵심 차이: 컨테이너 vs 가상머신(VM)

```
┌─────────────────────────────────────┐
│        가상머신 (VM)                  │
│  ┌──────────┐  ┌──────────┐         │
│  │  App A   │  │  App B   │         │
│  │  라이브러리 │  │  라이브러리 │        │
│  │  Guest OS │  │  Guest OS │  ← 각각 별도 OS (수 GB)
│  └──────────┘  └──────────┘         │
│  ┌─────────────────────────┐        │
│  │      Hypervisor          │        │
│  └─────────────────────────┘        │
│  ┌─────────────────────────┐        │
│  │      Host OS             │        │
│  └─────────────────────────┘        │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│        컨테이너 (Docker)              │
│  ┌──────────┐  ┌──────────┐         │
│  │  App A   │  │  App B   │         │
│  │  라이브러리 │  │  라이브러리 │        │
│  └──────────┘  └──────────┘         │
│  ┌─────────────────────────┐        │
│  │    Docker Engine         │  ← OS 공유 (수 MB)
│  └─────────────────────────┘        │
│  ┌─────────────────────────┐        │
│  │      Host OS             │        │
│  └─────────────────────────┘        │
└─────────────────────────────────────┘
```

| | 가상머신 (VM) | 컨테이너 (Docker) |
|---|---|---|
| 크기 | 수 GB | 수 MB ~ 수백 MB |
| 시작 시간 | 수 분 | 수 초 |
| OS | 각각 독립 OS | 호스트 OS 커널 공유 |
| 격리 수준 | 완전 격리 | 프로세스 수준 격리 |
| 성능 | 오버헤드 있음 | 네이티브에 가까움 |

---

## 2. 왜 필요한가?

### Before: Docker 없이 개발

```
팀원 A: "내 컴퓨터에선 잘 되는데?"
팀원 B: "Redis 버전이 달라서 안 돼요"
팀원 C: "Kafka 설치하는 데 2시간 걸렸어요"
신입사원: "환경 구축만 3일째..."
```

- Redis 7.4를 설치해야 하는데 6.x가 이미 설치되어 충돌
- Mac, Linux, Windows마다 설치 방법이 다름
- 특정 버전의 조합에서만 동작하는 버그

### After: Docker 사용

```bash
# 어떤 OS든, 누구든, 한 줄이면 전체 개발 환경 준비 완료
docker compose up -d

# 끝. Redis 7.4, Kafka 3.8, PostgreSQL 16 전부 알아서 실행됨
```

### 이 프로젝트에서 Docker Compose를 선택한 이유

1. **6개 인프라를 동시에 실행**: Redis, Kafka, PostgreSQL, Nginx, Prometheus, Grafana
2. **버전 고정**: `redis:7.4-alpine`, `apache/kafka:3.8.1` 등 정확한 버전 보장
3. **한 번에 시작/종료**: `up -d` / `down` 한 줄
4. **환경 분리**: 개발/운영 설정을 오버레이 파일로 분리

---

## 3. 핵심 개념

### 3.1 Image (이미지)

컨테이너를 만들기 위한 **읽기 전용 템플릿**이다.
설치 CD와 비슷하다. CD 자체는 바뀌지 않고, CD로 설치하면 실행 가능한 환경이 된다.

```
이미지: redis:7.4-alpine
  = Redis 7.4 + Alpine Linux 기반 + 기본 설정
  = Docker Hub에서 다운로드
```

**태그(Tag)**: 이미지의 버전을 구분하는 이름

```
redis:7.4-alpine    ← Redis 7.4, Alpine Linux 기반 (경량)
redis:7.4           ← Redis 7.4, Debian 기반
redis:latest        ← 최신 버전 (비추천: 버전이 바뀔 수 있음)
```

> **alpine**: 5MB짜리 초경량 리눅스 배포판. 일반 이미지(100MB+)보다 훨씬 가벼움

### 3.2 Container (컨테이너)

이미지를 실행한 **인스턴스**. 프로세스와 비슷하다.
같은 이미지에서 컨테이너를 여러 개 만들 수 있다.

```
이미지: redis:7.4-alpine (읽기 전용)
  │
  ├── 컨테이너 1: flashsale-redis (실행 중, 포트 6379)
  ├── 컨테이너 2: test-redis (실행 중, 포트 6380)
  └── 컨테이너 3: another-redis (정지됨)
```

### 3.3 Volume (볼륨)

컨테이너가 종료되면 내부 데이터는 **사라진다**.
볼륨은 데이터를 컨테이너 외부에 저장하여 **영속성**을 보장한다.

```
┌─────────────────────┐
│  컨테이너 (Redis)     │
│  /data ──────────────┼──── redis-data (볼륨, 호스트에 저장)
│  컨테이너 삭제해도    │      데이터는 남아있음
└─────────────────────┘
```

```yaml
volumes:
  redis-data:       # Named Volume: Docker가 관리하는 저장소
```

### 3.4 Port Mapping (포트 매핑)

컨테이너는 격리된 네트워크를 가진다. 외부에서 접근하려면 포트를 매핑해야 한다.

```
호스트 (내 컴퓨터)              컨테이너 (Redis)
┌──────────────────┐          ┌──────────────────┐
│                  │   6379   │                  │
│  localhost:6379 ─┼─────────►│  0.0.0.0:6379    │
│                  │          │                  │
└──────────────────┘          └──────────────────┘
```

```yaml
ports:
  - "6379:6379"     # 호스트포트:컨테이너포트
  - "8080:80"       # 호스트 8080 → 컨테이너 80
```

### 3.5 Network (네트워크)

Docker Compose의 서비스들은 기본적으로 **같은 네트워크**에 속한다.
서비스 이름으로 서로를 찾을 수 있다. (DNS 자동 등록)

```
┌────────────────────────────────────────┐
│  Docker Compose 기본 네트워크            │
│                                        │
│  nginx ──► kafka (서비스 이름으로 접근)   │
│    │                                   │
│    └──► redis                          │
│    └──► host.docker.internal:8080      │
│          (호스트의 애플리케이션)           │
└────────────────────────────────────────┘
```

### 3.6 Healthcheck (헬스체크)

컨테이너가 "실행 중"인 것과 "서비스 준비 완료"는 다르다.
Healthcheck는 서비스가 **실제로 요청을 받을 수 있는 상태인지** 확인한다.

```yaml
healthcheck:
  test: ["CMD", "redis-cli", "ping"]   # 이 명령의 종료 코드로 판단
  interval: 10s    # 10초마다 확인
  timeout: 5s      # 5초 안에 응답 없으면 실패
  retries: 5       # 5번 연속 실패 → unhealthy
  start_period: 0s # 시작 후 이 시간 동안은 실패해도 무시
```

상태 전이:
```
starting ──(healthcheck 성공)──► healthy
starting ──(retries 초과)──► unhealthy
healthy ──(healthcheck 실패, retries 초과)──► unhealthy
```

### 3.7 depends_on + condition

서비스 시작 순서를 제어한다.

```yaml
nginx:
  depends_on:
    redis:
      condition: service_healthy    # redis가 healthy가 될 때까지 대기
    kafka:
      condition: service_healthy    # kafka가 healthy가 될 때까지 대기
```

| condition | 의미 |
|-----------|------|
| `service_started` | 컨테이너 시작됨 (기본값, 준비 완료 보장 안 함) |
| `service_healthy` | healthcheck 통과 (준비 완료 보장) |
| `service_completed_successfully` | 컨테이너가 정상 종료됨 |

---

## 4. Docker Compose YAML 문법

### 4.1 기본 구조

```yaml
# docker-compose.yml

services:          # 서비스(컨테이너) 정의
  service-name:
    image: ...     # 사용할 이미지
    ports: ...     # 포트 매핑
    volumes: ...   # 데이터 영속화
    environment: ...  # 환경 변수
    command: ...   # 컨테이너 시작 시 실행할 명령
    depends_on: ...   # 의존 관계
    healthcheck: ...  # 건강 상태 확인
    restart: ...   # 재시작 정책

volumes:           # Named Volume 선언
  volume-name:
```

### 4.2 restart 정책

```yaml
restart: "no"              # 재시작 안 함 (기본값)
restart: always            # 항상 재시작 (수동 stop 제외)
restart: on-failure        # 비정상 종료(exit code ≠ 0)일 때만
restart: unless-stopped    # 수동으로 stop하지 않는 한 항상 ← 이 프로젝트
```

### 4.3 environment (환경 변수)

```yaml
# 방법 1: key: value
environment:
  POSTGRES_DB: flashsale
  POSTGRES_USER: flashsale
  POSTGRES_PASSWORD: flashsale123

# 방법 2: 파일에서 로드
env_file:
  - .env
```

### 4.4 command (시작 명령)

이미지의 기본 명령을 덮어쓴다.

```yaml
# redis 이미지의 기본 명령: redis-server
# 아래처럼 옵션을 추가할 수 있음
command: redis-server --appendonly yes --maxmemory 256mb --maxmemory-policy allkeys-lru

# 여러 줄로 쓰기 (Prometheus 예시)
command:
  - '--config.file=/etc/prometheus/prometheus.yml'
  - '--storage.tsdb.retention.time=7d'
```

### 4.5 volumes (바인드 마운트 vs Named Volume)

```yaml
services:
  nginx:
    volumes:
      # 바인드 마운트: 호스트 파일 → 컨테이너 경로
      # 설정 파일 등을 직접 관리할 때 사용
      - ./infra/nginx/nginx.conf:/etc/nginx/nginx.conf:ro   # :ro = 읽기 전용

  redis:
    volumes:
      # Named Volume: Docker가 관리하는 저장소
      # 데이터 영속화에 사용 (호스트 경로 몰라도 됨)
      - redis-data:/data

volumes:
  redis-data:    # Named Volume 선언 (여기서 선언해야 사용 가능)
```

### 4.6 오버레이 파일 (다중 Compose 파일)

개발 환경 설정을 유지하면서 운영 환경 설정만 **덮어쓰기/추가**한다.

```bash
# 개발 환경: 단일 인스턴스
docker compose up -d
# → docker-compose.yml만 사용

# 운영 환경: HA (고가용성) 구성
docker compose -f docker-compose.yml -f docker-compose.ha.yml up -d
# → docker-compose.yml 먼저 읽고, docker-compose.ha.yml로 덮어쓰기
```

```yaml
# docker-compose.ha.yml (운영 오버레이 예시)
services:
  redis-replica:            # 새 서비스 추가
    image: redis:7.4-alpine
    command: redis-server --replicaof redis 6379

  kafka:                    # 기존 서비스 설정 변경
    environment:
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 3   # 1 → 3으로 변경
```

---

## 5. 이 프로젝트에서의 활용

### 전체 서비스 구성

```
docker compose up -d  →  6개 컨테이너 실행:

┌──────────────────────────────────────────────────┐
│  Docker Compose Network                           │
│                                                   │
│  ┌─────────┐  ┌─────────┐  ┌───────────────────┐ │
│  │  Nginx  │  │  Redis  │  │     Kafka         │ │
│  │  :80    │  │  :6379  │  │  :9092 (KRaft)    │ │
│  └─────────┘  └─────────┘  └───────────────────┘ │
│  ┌───────────┐  ┌────────────┐  ┌─────────────┐  │
│  │ PostgreSQL│  │ Prometheus │  │   Grafana   │  │
│  │  :5432    │  │  :9090     │  │   :3000     │  │
│  └───────────┘  └────────────┘  └─────────────┘  │
└──────────────────────────────────────────────────┘
```

### 서비스별 역할

| 서비스 | 이미지 | 포트 | 역할 | 볼륨 |
|--------|--------|------|------|------|
| nginx | `nginx:1.27-alpine` | 80 | 리버스 프록시, Rate Limiting | 설정 파일 바인드 |
| redis | `redis:7.4-alpine` | 6379 | 캐시, 분산 락, 대기열 | `redis-data` |
| kafka | `apache/kafka:3.8.1` | 9092 | 이벤트 스트리밍 | `kafka-data` |
| postgres | `postgres:16-alpine` | 5432 | 주문/결제 영속 데이터 | `postgres-data` |
| prometheus | `prom/prometheus:v2.53.3` | 9090 | 메트릭 수집 | `prometheus-data` |
| grafana | `grafana/grafana:11.4.0` | 3000 | 모니터링 대시보드 | `grafana-data` |

### 시작 순서

```
1. redis, kafka, postgres     ← 의존성 없음, 동시 시작
2. (healthcheck 통과 대기)
3. nginx                      ← redis, kafka가 healthy일 때
4. prometheus                 ← redis, kafka가 started일 때
5. grafana                    ← prometheus가 started일 때
```

### 프로젝트 파일

| 파일 | 역할 |
|------|------|
| `docker-compose.yml` | 개발 환경 (단일 인스턴스) |
| `docker-compose.ha.yml` | 운영 환경 오버레이 (이중화) |
| `infra/nginx/nginx.conf` | Nginx 설정 (바인드 마운트) |
| `infra/prometheus/prometheus.yml` | Prometheus 수집 대상 설정 |

---

## 6. 명령어 레퍼런스

### 기본 명령어

```bash
# 전체 시작 (백그라운드)
docker compose up -d

# 전체 종료 (데이터는 볼륨에 남음)
docker compose down

# 전체 종료 + 볼륨 삭제 (데이터 완전 초기화)
docker compose down -v

# 상태 확인
docker compose ps

# 모든 로그 보기 (실시간)
docker compose logs -f

# 특정 서비스 로그만 보기
docker compose logs -f kafka
docker compose logs -f redis --tail=100    # 최근 100줄부터
```

### 개별 서비스 관리

```bash
# 특정 서비스만 시작
docker compose up -d redis kafka

# 특정 서비스 재시작
docker compose restart redis

# 특정 서비스 중지
docker compose stop redis

# 특정 서비스 제거
docker compose rm redis
```

### 디버깅 / 접속

```bash
# 컨테이너 안에서 명령 실행
docker exec -it flashsale-redis redis-cli              # Redis CLI
docker exec -it flashsale-postgres psql -U flashsale   # PostgreSQL CLI
docker exec -it flashsale-kafka /bin/bash               # Kafka 셸 접속

# 컨테이너 리소스 사용량 확인
docker stats

# 이미지 다시 빌드 (Dockerfile 변경 시)
docker compose build
docker compose up -d --build    # 빌드 + 시작
```

### 정리

```bash
# 사용하지 않는 이미지 삭제
docker image prune

# 사용하지 않는 볼륨 삭제
docker volume prune

# 모든 미사용 리소스 삭제 (주의!)
docker system prune -a
```

---

## 7. 자주 하는 실수 / 주의사항

### 포트 충돌

```bash
# ❌ 호스트에서 이미 6379 포트를 사용 중이면 에러
Error: Bind for 0.0.0.0:6379 failed: port is already allocated

# ✅ 호스트 포트를 변경
ports:
  - "16379:6379"    # 호스트 16379 → 컨테이너 6379
```

### latest 태그 사용 금지

```yaml
# ❌ 버전이 언제든 바뀔 수 있음
image: redis:latest

# ✅ 버전 고정
image: redis:7.4-alpine
```

### 볼륨 삭제 주의

```bash
# ❌ 데이터가 전부 날아감!
docker compose down -v

# ✅ 데이터를 유지하면서 종료
docker compose down
```

### healthcheck 없이 depends_on 사용

```yaml
# ❌ 컨테이너 "시작"만 확인, 실제 서비스 준비 여부 모름
depends_on:
  - redis

# ✅ 서비스가 실제로 준비될 때까지 대기
depends_on:
  redis:
    condition: service_healthy
```

### 컨테이너 내부에서 localhost

```
# ❌ 컨테이너 안에서 localhost = 컨테이너 자신
# 호스트의 애플리케이션에 접근 불가

# ✅ 호스트에 접근하려면
host.docker.internal    # Docker Desktop (Mac/Windows)
172.17.0.1              # Linux (docker0 브릿지)
```

---

## 8. 정리 / 한눈에 보기

### 핵심 개념 요약

| 개념 | 비유 | 설명 |
|------|------|------|
| Image | 설치 CD | 읽기 전용 템플릿 |
| Container | 실행 중인 프로그램 | 이미지의 인스턴스 |
| Volume | 외장 하드 | 컨테이너 외부 데이터 저장소 |
| Port Mapping | 전화 착신전환 | 호스트 포트 → 컨테이너 포트 |
| Network | 사내 전화망 | 서비스 간 통신 네트워크 |
| Healthcheck | 건강 검진 | 서비스 준비 상태 확인 |

### 명령어 치트시트

| 하고 싶은 것 | 명령어 |
|-------------|--------|
| 전체 시작 | `docker compose up -d` |
| 전체 종료 | `docker compose down` |
| 상태 확인 | `docker compose ps` |
| 로그 보기 | `docker compose logs -f [서비스]` |
| 컨테이너 접속 | `docker exec -it [이름] [셸]` |
| 데이터 초기화 | `docker compose down -v` |

---

## 9. 더 알아보기

- [Docker 공식 문서](https://docs.docker.com/)
- [Docker Compose 공식 문서](https://docs.docker.com/compose/)
- [Docker Hub](https://hub.docker.com/) — 공식 이미지 검색
