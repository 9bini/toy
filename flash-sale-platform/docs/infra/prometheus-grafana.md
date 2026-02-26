# Prometheus + Grafana

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [Prometheus 핵심 개념](#3-prometheus-핵심-개념)
4. [메트릭 타입](#4-메트릭-타입)
5. [PromQL (쿼리 언어)](#5-promql-쿼리-언어)
6. [Grafana 핵심 개념](#6-grafana-핵심-개념)
7. [이 프로젝트에서의 활용](#7-이-프로젝트에서의-활용)
8. [자주 하는 실수 / 주의사항](#8-자주-하는-실수--주의사항)
9. [정리 / 한눈에 보기](#9-정리--한눈에-보기)
10. [더 알아보기](#10-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

- **Prometheus**: 서비스에서 숫자 데이터(메트릭)를 수집하고 저장하는 모니터링 서버
- **Grafana**: Prometheus에 저장된 데이터를 그래프와 대시보드로 시각화하는 도구

### 비유: 건강 검진

- **Prometheus** = 건강 검진 기계: 정기적으로 혈압, 맥박, 체온 등을 측정하고 기록
- **Grafana** = 검진 결과지: 측정 데이터를 그래프로 보여주고, 이상 수치 시 알림

```
서비스 (환자)           Prometheus (검진 기계)      Grafana (결과지)
┌──────────┐          ┌──────────────┐          ┌──────────────┐
│ 주문 서비스 │ ←(수집)── │  15초마다 측정  │ ──(조회)─► │  📊 그래프    │
│ 결제 서비스 │ ←(수집)── │  시계열 DB 저장 │          │  📈 대시보드   │
│ Gateway   │ ←(수집)── │              │          │  🔔 알림      │
└──────────┘          └──────────────┘          └──────────────┘
```

---

## 2. 왜 필요한가?

### Before: 모니터링 없이

```
"서비스가 느려요"
→ "로그 확인해볼게요... 어디서 느린 거지?"
→ "CPU가 100%인데 왜 그런 거지?"
→ "어제부터 메모리 누수가 있었네... 진작 알았으면..."
```

### After: 모니터링 시스템 사용

```
대시보드에서 즉시 확인:
  - HTTP 요청: 초당 5,000건, 평균 응답 50ms, 에러율 0.1%
  - JVM: 힙 메모리 70%, GC 정상
  - 서킷 브레이커: CLOSED (정상)
  - Redis: 연결 정상, 응답 0.5ms

알림: "주문 서비스 에러율이 5%를 초과했습니다!" → 즉시 대응
```

---

## 3. Prometheus 핵심 개념

### 3.1 Pull 모델

Prometheus가 서비스에 **직접 요청하여** 메트릭을 가져온다.

```
Push 모델 (다른 시스템):
  서비스 ──(보냄)──► 모니터링 서버
  "나 데이터 줄게!"

Pull 모델 (Prometheus):          ← 이 방식
  Prometheus ──(가져감)──► 서비스
  "너 데이터 줘!"
```

**Pull의 장점**:
- 서비스는 데이터를 노출만 하면 됨 (발송 로직 불필요)
- Prometheus가 수집 주기를 제어
- 서비스가 죽으면 수집 실패 → "서비스 다운" 감지 가능

### 3.2 Scrape (스크레이프)

Prometheus가 서비스의 메트릭 엔드포인트를 호출하여 데이터를 수집하는 동작.

```
15초마다:
  Prometheus → GET http://order-service:8082/actuator/prometheus

  응답 (텍스트 형식):
  # HELP http_server_requests_seconds HTTP 요청 처리 시간
  # TYPE http_server_requests_seconds histogram
  http_server_requests_seconds_count{uri="/api/orders",status="200"} 15234
  http_server_requests_seconds_sum{uri="/api/orders",status="200"} 456.78
```

### 3.3 Time Series (시계열)

메트릭 이름 + 라벨(태그) + 시간별 값으로 구성된 데이터.

```
메트릭 이름                     라벨                              값
http_server_requests_seconds  {uri="/api/orders", status="200"}  @시각1: 100
                                                                 @시각2: 105
                                                                 @시각3: 112
```

### 3.4 Label (라벨)

같은 메트릭을 **다양한 차원으로 구분**하는 태그.

```
http_server_requests_seconds_count{uri="/api/orders", method="POST", status="200"} 5000
http_server_requests_seconds_count{uri="/api/orders", method="POST", status="500"} 10
http_server_requests_seconds_count{uri="/api/queue",  method="GET",  status="200"} 80000
```

→ "주문 API의 500 에러만" 필터링 가능

---

## 4. 메트릭 타입

### 4.1 Counter (카운터)

**단조 증가**하는 값. 절대 줄어들지 않음. (서비스 재시작 시 0으로 리셋)

```
총 요청 수:     100 → 101 → 102 → 103 → ...
총 에러 수:     5   → 5   → 6   → 6   → ...
총 바이트 전송: 1MB → 2MB → 3MB → ...
```

> Counter 자체는 누적값이므로, `rate()` 함수로 **초당 증가율**을 구한다.

### 4.2 Gauge (게이지)

**오르내릴 수 있는** 값. 현재 상태를 나타냄.

```
현재 메모리 사용량:  256MB → 300MB → 250MB → 280MB
현재 활성 연결 수:   50    → 80    → 30    → 65
현재 온도:          36.5  → 37.0  → 36.8
```

### 4.3 Histogram (히스토그램)

값의 **분포**를 측정. 구간(bucket)별 개수를 기록.

```
HTTP 응답 시간 분포:
  0~10ms:    5000건
  10~50ms:   3000건
  50~100ms:  500건
  100~500ms: 100건
  500ms+:    10건
```

→ P50, P95, P99 백분위수(percentile) 계산 가능

### 4.4 Summary (요약)

Histogram과 비슷하지만, 클라이언트(서비스) 측에서 백분위수를 미리 계산.

| | Histogram | Summary |
|---|---|---|
| 계산 위치 | Prometheus (서버) | 서비스 (클라이언트) |
| 정확도 | 근사값 | 정확 |
| 집계 | 여러 인스턴스 합산 가능 | 합산 불가 |
| 추천 | ✅ 일반적 | 특수한 경우만 |

---

## 5. PromQL (쿼리 언어)

### 5.1 기본 조회

```promql
# 메트릭 이름으로 조회
http_server_requests_seconds_count

# 라벨 필터링
http_server_requests_seconds_count{status="200"}
http_server_requests_seconds_count{uri="/api/orders", status=~"5.."}
#                                                       ↑ 정규식 (500, 501, 502...)
```

### 5.2 rate() — 초당 증가율

Counter 메트릭의 **초당 변화율**을 계산한다.

```promql
# 최근 5분간 초당 요청 수
rate(http_server_requests_seconds_count[5m])

# 의미: 5분 동안 Counter가 얼마나 증가했는지 → 초당으로 환산
# 예: 5분간 1500 증가 → rate = 1500 / 300 = 5 req/s
```

### 5.3 sum() — 합계

여러 시계열을 합산한다.

```promql
# 모든 URI의 초당 요청 수 합계
sum(rate(http_server_requests_seconds_count[5m]))

# 상태 코드별로 그룹화
sum by (status) (rate(http_server_requests_seconds_count[5m]))
```

### 5.4 histogram_quantile() — 백분위수

```promql
# 99% 응답 시간 (100건 중 99번째로 빠른 응답 시간)
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))

# 95% 응답 시간
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))

# 50% (중앙값)
histogram_quantile(0.50, rate(http_server_requests_seconds_bucket[5m]))
```

### 5.5 실용적인 쿼리 모음

```promql
# 1. 초당 요청 수 (QPS)
sum(rate(http_server_requests_seconds_count[5m]))

# 2. 에러율 (%)
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
/ sum(rate(http_server_requests_seconds_count[5m])) * 100

# 3. 평균 응답 시간 (ms)
sum(rate(http_server_requests_seconds_sum[5m]))
/ sum(rate(http_server_requests_seconds_count[5m])) * 1000

# 4. P99 응답 시간 (ms)
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket[5m]))) * 1000

# 5. JVM 힙 메모리 사용률 (%)
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} * 100

# 6. 활성 스레드 수
jvm_threads_live_threads

# 8. GC 일시 중지 시간
rate(jvm_gc_pause_seconds_sum[5m])
```

---

## 6. Grafana 핵심 개념

### 6.1 접속

```
URL: http://localhost:3000
기본 계정: admin / admin
```

### 6.2 Data Source (데이터 소스)

Grafana가 데이터를 가져오는 출처. 이 프로젝트에서는 Prometheus.

```
Grafana → Data Source: Prometheus (http://prometheus:9090)
```

### 6.3 Dashboard (대시보드)

여러 패널(그래프)을 모아놓은 화면.

```
┌─────────────────────────────────────────┐
│  Flash Sale 모니터링 대시보드              │
│ ┌──────────────┐ ┌──────────────────┐   │
│ │ 초당 요청 수   │ │ 응답 시간 (P50/99)│   │
│ │   📈         │ │   📈             │   │
│ └──────────────┘ └──────────────────┘   │
│ ┌──────────────┐ ┌──────────────────┐   │
│ │ 에러율 (%)    │ │ 서킷 브레이커 상태 │   │
│ │   🔴 2.1%    │ │   🟢 CLOSED     │   │
│ └──────────────┘ └──────────────────┘   │
│ ┌──────────────┐ ┌──────────────────┐   │
│ │ JVM 메모리    │ │ Redis 응답 시간   │   │
│ │   📊 70%     │ │   📈 0.5ms       │   │
│ └──────────────┘ └──────────────────┘   │
└─────────────────────────────────────────┘
```

### 6.4 Panel (패널)

대시보드 내의 개별 시각화 요소.

| 패널 타입 | 용도 | 예시 |
|----------|------|------|
| Time Series | 시간별 추이 | 요청 수, 응답 시간 |
| Gauge | 현재 값 | CPU 사용률, 에러율 |
| Stat | 단일 숫자 | 총 요청 수, 가동 시간 |
| Table | 표 형식 | 엔드포인트별 통계 |
| Alert List | 알림 목록 | 발생 중인 알림 |

### 6.5 Alert (알림)

특정 조건이 충족되면 알림을 보낸다.

```
조건: 에러율 > 5% 이 상태가 5분 이상 지속
동작: Slack 채널에 메시지 전송
```

---

## 7. 이 프로젝트에서의 활용

### Prometheus 설정

```yaml
# infra/prometheus/prometheus.yml
global:
  scrape_interval: 15s    # 15초마다 수집

scrape_configs:
  - job_name: 'gateway'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']

  - job_name: 'queue-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8081']

  - job_name: 'order-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8082']

  - job_name: 'payment-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8083']
```

### 서비스에서 메트릭 노출

```kotlin
// build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-actuator")
implementation("io.micrometer:micrometer-registry-prometheus")
```

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus    # 엔드포인트 노출
  metrics:
    export:
      prometheus:
        enabled: true
```

→ `http://서비스:포트/actuator/prometheus` 에서 메트릭 노출

### Docker 설정

```yaml
prometheus:
  image: prom/prometheus:v2.53.3
  ports: ["9090:9090"]
  volumes:
    - ./infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
  command:
    - '--config.file=/etc/prometheus/prometheus.yml'
    - '--storage.tsdb.retention.time=7d'    # 7일 보관

grafana:
  image: grafana/grafana:11.4.0
  ports: ["3000:3000"]
  environment:
    GF_SECURITY_ADMIN_USER: admin
    GF_SECURITY_ADMIN_PASSWORD: admin
```

---

## 8. 자주 하는 실수 / 주의사항

### Counter에 rate() 없이 사용

```promql
# ❌ Counter 원시값은 계속 증가 → 의미 없는 그래프
http_server_requests_seconds_count

# ✅ rate()로 초당 변화율 계산
rate(http_server_requests_seconds_count[5m])
```

### [5m] 범위의 의미

```promql
# rate()의 범위 = 평활화 수준
rate(metric[1m])   # 1분: 변동이 심함 (노이즈 많음)
rate(metric[5m])   # 5분: 적절한 평활화 ← 권장
rate(metric[1h])   # 1시간: 변동이 거의 없음 (피크를 못 잡음)
```

### Prometheus가 서비스 메트릭을 못 가져옴

```
# 확인 1: 서비스에서 메트릭 엔드포인트가 열려 있는지
curl http://localhost:8082/actuator/prometheus

# 확인 2: Prometheus가 서비스에 접근할 수 있는지
# Docker 환경에서는 host.docker.internal 사용

# 확인 3: Prometheus 타겟 상태 확인
http://localhost:9090/targets  ← 브라우저에서 확인
```

---

## 9. 정리 / 한눈에 보기

### 구성 요약

| 구성 요소 | 역할 | 접속 주소 |
|----------|------|----------|
| Prometheus | 메트릭 수집 + 저장 | http://localhost:9090 |
| Grafana | 시각화 + 알림 | http://localhost:3000 |
| Actuator | 서비스 메트릭 노출 | http://서비스/actuator/prometheus |

### 메트릭 타입 요약

| 타입 | 동작 | 예시 | PromQL |
|------|------|------|--------|
| Counter | 증가만 | 요청 수, 에러 수 | `rate(counter[5m])` |
| Gauge | 오르내림 | 메모리, 연결 수 | 그대로 사용 |
| Histogram | 분포 | 응답 시간 | `histogram_quantile()` |

### PromQL 치트시트

| 하고 싶은 것 | 쿼리 |
|-------------|------|
| 초당 요청 수 | `sum(rate(http_..._count[5m]))` |
| 에러율 (%) | `sum(rate(...{status=~"5.."}[5m])) / sum(rate(...[5m])) * 100` |
| P99 응답 시간 | `histogram_quantile(0.99, sum by (le) (rate(..._bucket[5m])))` |
| 메모리 사용률 | `jvm_memory_used_bytes / jvm_memory_max_bytes * 100` |

---

## 10. 더 알아보기

- [Prometheus 공식 문서](https://prometheus.io/docs/)
- [PromQL 튜토리얼](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [Grafana 공식 문서](https://grafana.com/docs/)
