# Nginx

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [핵심 개념](#3-핵심-개념)
4. [nginx.conf 구조](#4-nginxconf-구조)
5. [이 프로젝트에서의 활용](#5-이-프로젝트에서의-활용)
6. [기본 사용법](#6-기본-사용법)
7. [자주 하는 실수 / 주의사항](#7-자주-하는-실수--주의사항)
8. [정리 / 한눈에 보기](#8-정리--한눈에-보기)
9. [더 알아보기](#9-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

클라이언트(브라우저)와 백엔드 서비스 사이에서 **요청을 대신 전달하고, 트래픽을 관리하는 중간 서버**다.

### 비유: 호텔 프론트 데스크

손님(클라이언트)이 호텔에 오면 각 방(서비스)을 직접 찾아다니지 않는다.
**프론트 데스크(Nginx)**에서 요청을 받아서 적절한 부서로 전달한다.

- 식당 예약? → 식당으로 전달 (라우팅)
- 같은 요청이 너무 많으면? → "잠시 기다려주세요" (Rate Limiting)
- 직원이 여러 명이면? → 가장 한가한 직원에게 배정 (로드밸런싱)

### 웹서버 vs 리버스 프록시

Nginx는 두 가지 역할을 할 수 있다:

| 역할 | 하는 일 | 예시 |
|------|---------|------|
| **웹서버** | 정적 파일(HTML, CSS, 이미지)을 직접 응답 | 블로그, 랜딩 페이지 |
| **리버스 프록시** | 요청을 뒤의 서비스로 전달 | **이 프로젝트** |

이 프로젝트에서는 **리버스 프록시**로만 사용한다.

---

## 2. 왜 필요한가?

### Before: Nginx 없이

```
사용자 → 주문서비스:8082    (직접 접근)
사용자 → 결제서비스:8083    (직접 접근)
사용자 → 대기열서비스:8081  (직접 접근)

문제점:
1. 봇이 초당 1만 건 요청 → 서비스 다운
2. 서비스 포트를 외부에 노출해야 함
3. 서비스 인스턴스 추가 시 클라이언트 변경 필요
```

### After: Nginx 사용

```
사용자 → Nginx:80 → 주문서비스:8082
                  → 결제서비스:8083
                  → 대기열서비스:8081

해결:
1. Nginx가 IP당 요청 수 제한 (Rate Limiting)
2. 외부에는 80 포트만 노출
3. 서비스 추가/제거를 Nginx 설정만으로 처리
```

### 이 프로젝트에서 Nginx의 3가지 역할

1. **Rate Limiting** — 봇/매크로 방어 (1차 방어선)
2. **로드밸런싱** — 여러 서비스 인스턴스에 요청 분배
3. **SSE 프록시** — 실시간 이벤트 스트리밍 중계

---

## 3. 핵심 개념

### 3.1 정방향 프록시 vs 리버스 프록시

```
정방향 프록시 (Forward Proxy):
  사용자 → [프록시] → 인터넷
  사용자의 IP를 숨김 (VPN과 비슷)
  "내가 누군지 모르게 해줘"

리버스 프록시 (Reverse Proxy):     ← Nginx가 하는 역할
  사용자 → [프록시] → 서비스들
  서비스의 존재를 숨김
  "뒤에 서비스가 몇 개인지 몰라도 돼"
```

### 3.2 Rate Limiting (요청 제한)

**개념**: IP 주소별로 일정 시간 동안 허용하는 요청 수를 제한한다.

**Leaky Bucket 알고리즘**:

```
물(요청)이 양동이(bucket)에 쌓이고, 일정 속도로 빠져나간다.
양동이가 넘치면(burst 초과) 요청을 거부한다(503).

    요청 ──►  ┌─────┐
    요청 ──►  │     │ ← burst (버퍼 크기)
    요청 ──►  │ ○○○ │
              │ ○○  │
              └──┬──┘
                 │
                 ▼  rate (초당 처리량)
              처리됨
```

```nginx
# 1단계: zone 정의 (메모리에 IP별 요청 카운터 저장)
limit_req_zone $binary_remote_addr zone=api_rate:10m rate=50r/s;
#              ↑ IP 주소 기준       ↑ zone 이름:메모리  ↑ 초당 50건

# 2단계: location에 적용
location /api/ {
    limit_req zone=api_rate burst=100 nodelay;
    #                       ↑ 100건 버퍼  ↑ 버퍼 내 즉시 처리
}
```

| 파라미터 | 설명 | 예시 |
|---------|------|------|
| `$binary_remote_addr` | 클라이언트 IP (바이너리, 메모리 절약) | 192.168.1.1 |
| `zone=이름:크기` | 카운터 저장 메모리 영역 | `api_rate:10m` (~16만 IP) |
| `rate=Nr/s` | 초당 허용 요청 수 | `50r/s` |
| `burst=N` | 순간 초과 허용 수 (버퍼) | `burst=100` |
| `nodelay` | burst 내 요청은 지연 없이 즉시 처리 | |

### 3.3 upstream (로드밸런싱)

여러 서버(인스턴스)에 요청을 분배하는 설정이다.

```nginx
upstream queue_service {
    least_conn;              # 분배 전략
    server host.docker.internal:8081;
    server host.docker.internal:8082;
    keepalive 64;            # 연결 재사용 풀 크기
}

server {
    location /api/queue/ {
        proxy_pass http://queue_service;   # upstream으로 전달
    }
}
```

**분배 전략**:

| 전략 | 동작 | 적합한 경우 |
|------|------|-----------|
| round-robin (기본) | 순서대로 돌아가며 | 요청 처리 시간이 비슷할 때 |
| `least_conn` | 연결 수 가장 적은 서버로 | SSE처럼 장시간 연결 |
| `ip_hash` | 같은 IP → 같은 서버 | 세션 유지 필요 시 |
| `hash $key` | 특정 키 기반 | 캐시 히트율 극대화 |

### 3.4 SSE (Server-Sent Events) 프록시

SSE는 서버가 클라이언트에 **실시간으로 이벤트를 보내는 장시간 HTTP 연결**이다.
일반 HTTP 프록시 설정과 다른 특별한 설정이 필요하다.

```
일반 HTTP:
  요청 → 응답 → 연결 끊김  (수 ms)

SSE:
  요청 → 응답(이벤트1) → 응답(이벤트2) → ... → 연결 유지  (수 분)
```

```nginx
location /api/queue/ {
    proxy_read_timeout 300s;    # 5분 유지 (기본 60초 → 연결 끊김 방지)
    proxy_buffering off;        # 버퍼링 OFF → 이벤트 즉시 전달
    proxy_cache off;            # 캐시 OFF → 실시간 데이터
    chunked_transfer_encoding on;

    # HTTP 1.1 + Connection 헤더 정리 (keep-alive 지원)
    proxy_http_version 1.1;
    proxy_set_header Connection "";
}
```

| 설정 | 왜 필요한가 |
|------|-----------|
| `proxy_read_timeout 300s` | 기본 60초 → SSE 연결이 1분 만에 끊김 |
| `proxy_buffering off` | 이벤트를 모아서 보내면 실시간성 상실 |
| `proxy_cache off` | 캐시된 오래된 이벤트를 보내면 안 됨 |
| `proxy_http_version 1.1` | HTTP/1.0은 keep-alive 미지원 |

### 3.5 keepalive

Nginx와 백엔드 서비스 간 **연결을 재사용**하여 성능을 높인다.

```
keepalive 없이:
  매 요청마다 TCP 연결 생성 → 요청 → 응답 → 연결 종료
  (TCP 3-way handshake 오버헤드)

keepalive 사용:
  연결 생성 → 요청1 → 응답1 → 요청2 → 응답2 → ... → 일정 시간 후 종료
  (연결 재사용으로 지연 시간 감소)
```

```nginx
upstream backend {
    server host.docker.internal:8080;
    keepalive 64;    # 최대 64개 연결을 풀에 유지
}
```

---

## 4. nginx.conf 구조

### 전체 구조 (블록 계층)

```nginx
# 최상위: 전역 설정
worker_processes auto;          # CPU 코어 수만큼 워커

events {                        # 연결 처리 설정
    worker_connections 16384;
    use epoll;
}

http {                          # HTTP 프로토콜 설정
    # 공통 설정 (로그, 타임아웃 등)

    limit_req_zone ...;         # Rate Limiting zone 정의

    upstream backend { ... }    # 백엔드 서버 그룹

    server {                    # 가상 서버 (포트별)
        listen 80;

        location /api/ { ... }           # URL 패턴별 처리
        location /api/orders { ... }
        location = /health { ... }
    }
}
```

### 각 블록의 역할

| 블록 | 역할 | 위치 |
|------|------|------|
| 최상위 | 워커 프로세스 수, 파일 제한 | 파일 시작 |
| `events` | 동시 연결 수, I/O 모델 | 최상위 안 |
| `http` | HTTP 공통 설정, MIME 타입 | 최상위 안 |
| `upstream` | 백엔드 서버 그룹 정의 | `http` 안 |
| `server` | 가상 서버 (포트, 도메인) | `http` 안 |
| `location` | URL 패턴별 처리 규칙 | `server` 안 |

### location 매칭 우선순위

```nginx
location = /exact          { }   # 1순위: 정확히 일치
location ^~ /prefix        { }   # 2순위: 접두사 (정규식보다 우선)
location ~ /regex          { }   # 3순위: 정규식 (대소문자 구분)
location ~* /regex         { }   # 3순위: 정규식 (대소문자 무시)
location /prefix           { }   # 4순위: 접두사 (가장 긴 것 우선)
```

```nginx
# 예시: /api/orders/123 요청이 오면

location /api/ { }           # 매칭됨 (접두사)
location /api/orders { }     # 매칭됨 (더 긴 접두사 → 이것 선택)
location = /api/orders { }   # 매칭 안 됨 (정확히 /api/orders만)
```

---

## 5. 이 프로젝트에서의 활용

### 전체 요청 흐름

```
사용자 → Nginx:80
          │
          ├─ /api/queue/**  ──► Queue Service:8081  (SSE, least_conn)
          ├─ /api/orders/** ──► Gateway:8080         (Rate Limit: 5r/s)
          ├─ /api/**        ──► Gateway:8080         (Rate Limit: 50r/s)
          ├─ /actuator/**   ──► Gateway:8080         (메트릭)
          └─ /health        ──► 200 OK (Nginx 자체 응답)
```

### Rate Limiting 2단계 방어

```
요청 → [Nginx Rate Limit]  → [서비스 Rate Limit (Resilience4j)]
        IP 기반, 초당 N건      사용자/API 기반, 세밀한 제어
        1차 방어선 (거친 필터)    2차 방어선 (정밀 필터)
```

### Docker Compose 설정

```yaml
nginx:
  image: nginx:1.27-alpine
  container_name: flashsale-nginx
  ports:
    - "80:80"
  volumes:
    - ./infra/nginx/nginx.conf:/etc/nginx/nginx.conf:ro   # 설정 파일 주입
  depends_on:
    redis:
      condition: service_healthy
    kafka:
      condition: service_healthy
```

### 프로젝트 파일

- `infra/nginx/nginx.conf` — Nginx 전체 설정
- `docker-compose.yml` — nginx 서비스 정의 (포트 80)

---

## 6. 기본 사용법

### 설정 테스트

```bash
# 문법 검사 (실제 적용 전 확인)
docker exec flashsale-nginx nginx -t
# nginx: the configuration file /etc/nginx/nginx.conf syntax is ok

# 설정 리로드 (무중단)
docker exec flashsale-nginx nginx -s reload
```

### 로그 확인

```bash
# 실시간 로그
docker compose logs -f nginx

# access 로그: 모든 요청 기록
# error 로그: 에러만 기록
```

### Rate Limiting 테스트

```bash
# 초당 50건 이상 요청 보내기
for i in $(seq 1 100); do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost/api/test
done

# 정상: 200
# Rate Limit 초과: 503
```

---

## 7. 자주 하는 실수 / 주의사항

### proxy_pass 끝에 슬래시

```nginx
# ❌ /api/orders/123 → 백엔드에 /orders/123 으로 전달 (경로 잘림)
location /api/ {
    proxy_pass http://backend/;
}

# ✅ /api/orders/123 → 백엔드에 /api/orders/123 그대로 전달
location /api/ {
    proxy_pass http://backend;
}
```

### SSE 연결이 1분 만에 끊김

```nginx
# ❌ 기본 proxy_read_timeout = 60s
location /api/queue/ {
    proxy_pass http://queue_service;
}

# ✅ 타임아웃 연장
location /api/queue/ {
    proxy_read_timeout 300s;    # 5분
    proxy_buffering off;        # 이벤트 즉시 전달
    proxy_pass http://queue_service;
}
```

### 설정 변경 후 반영 안 됨

```bash
# ❌ 설정 파일 수정했는데 반영 안 됨
# (Nginx는 시작 시 설정을 메모리에 로드)

# ✅ 리로드 필요
docker exec flashsale-nginx nginx -s reload
# 또는 컨테이너 재시작
docker compose restart nginx
```

---

## 8. 정리 / 한눈에 보기

### Nginx 역할 요약

| 기능 | 설명 | 이 프로젝트에서 |
|------|------|---------------|
| 리버스 프록시 | 요청을 백엔드로 전달 | 모든 /api/ 요청 → Gateway/Service |
| Rate Limiting | IP별 요청 수 제한 | 일반 API 50r/s, 주문 5r/s |
| 로드밸런싱 | 여러 인스턴스에 분배 | least_conn (SSE용) |
| SSE 프록시 | 장시간 연결 중계 | 대기열 실시간 알림 |
| 헬스체크 | 자체 상태 응답 | `/health` → 200 OK |

### 핵심 설정 파라미터

| 설정 | 값 | 의미 |
|------|-----|------|
| `worker_processes` | auto | CPU 코어 수만큼 |
| `worker_connections` | 16384 | 워커당 동시 연결 수 |
| `use epoll` | - | Linux 고성능 I/O |
| `keepalive` | 64 | 백엔드 연결 재사용 풀 |
| `proxy_read_timeout` | 300s (SSE) | SSE 연결 유지 시간 |

---

## 9. 더 알아보기

- [Nginx 공식 문서](https://nginx.org/en/docs/)
- [Nginx Rate Limiting 가이드](https://www.nginx.com/blog/rate-limiting-nginx/)
- [Nginx Reverse Proxy 가이드](https://docs.nginx.com/nginx/admin-guide/web-server/reverse-proxy/)
