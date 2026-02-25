# Apache Kafka

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [핵심 개념](#3-핵심-개념)
4. [Producer (메시지 발행)](#4-producer-메시지-발행)
5. [Consumer (메시지 소비)](#5-consumer-메시지-소비)
6. [KRaft 모드](#6-kraft-모드)
7. [이 프로젝트에서의 활용](#7-이-프로젝트에서의-활용)
8. [HA와 Replication](#8-ha와-replication)
9. [자주 하는 실수 / 주의사항](#9-자주-하는-실수--주의사항)
10. [정리 / 한눈에 보기](#10-정리--한눈에-보기)
11. [더 알아보기](#11-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

서비스 간에 **메시지(이벤트)를 안정적으로 전달하고 보관**하는 분산 이벤트 스트리밍 플랫폼.

### 비유: 우체국 + 보관함

- **HTTP 직접 호출** = 전화: 상대방이 안 받으면 실패
- **Kafka** = 우체국: 편지를 보관함에 넣어두면, 수신자가 **나중에라도** 가져감

```
전화 (HTTP):
  "지금 당장 받아!" → 상대방 부재중 → 실패

우체국 (Kafka):
  편지 투입 → [보관함] → 수신자가 원할 때 수거
                         (몇 시간 후에도 OK)
```

### 메시지 큐 vs 이벤트 스트리밍

| | 전통적 메시지 큐 (RabbitMQ) | 이벤트 스트리밍 (Kafka) |
|---|---|---|
| 메시지 소비 후 | **삭제됨** | **보관됨** (설정된 기간만큼) |
| 다수 소비자 | 하나만 소비 | 여러 소비자가 독립적으로 소비 |
| 용도 | 작업 분배 | 이벤트 기록 + 분배 |
| 처리량 | 수천 msg/s | **수백만 msg/s** |
| 순서 보장 | 큐 전체 | **파티션 내** |

---

## 2. 왜 필요한가?

### Before: 서비스 간 HTTP 직접 호출

```
주문 서비스 ──HTTP──► 결제 서비스 (다운!) → 주문도 실패
              ──HTTP──► 알림 서비스 (느림!) → 주문이 3초 대기

문제점:
1. 결합도: 결제서비스 죽으면 주문서비스도 죽음
2. 동기 대기: 결제 완료까지 사용자가 대기
3. 확장성: 새 서비스 추가 시 주문서비스 코드 수정 필요
```

### After: Kafka 사용

```
주문 서비스 ──► [Kafka: order.placed] ──► 결제 서비스 (다운이어도 OK)
                                      ──► 알림 서비스
                                      ──► 재고 서비스

장점:
1. 결합 해소: 주문서비스는 Kafka에 쓰기만 하면 끝
2. 비동기: 사용자는 즉시 응답 받음
3. 확장성: 새 서비스는 토픽 구독만 추가
4. 안정성: 결제서비스가 복구 후 밀린 메시지 처리
```

---

## 3. 핵심 개념

### 3.1 Topic (토픽)

메시지의 **카테고리(주제)**. 메시지를 분류하는 폴더와 비슷하다.

```
Topic: flashsale.order.placed      → 주문 생성 이벤트
Topic: flashsale.payment.completed → 결제 완료 이벤트
Topic: flashsale.stock.decremented → 재고 차감 이벤트
```

### 3.2 Partition (파티션)

토픽을 **여러 조각으로 나눈 것**. 병렬 처리의 단위.

```
Topic: flashsale.order.placed
┌───────────────────────────────────┐
│ Partition 0: [주문A] [주문D] [주문G] │  → Consumer 1이 처리
│ Partition 1: [주문B] [주문E] [주문H] │  → Consumer 2가 처리
│ Partition 2: [주문C] [주문F] [주문I] │  → Consumer 3이 처리
└───────────────────────────────────┘
```

**파티션이 3개면 → Consumer도 최대 3개가 병렬로 처리 가능**

### 파티션 키 (Partition Key)

같은 키를 가진 메시지는 **항상 같은 파티션**에 들어간다.

```
key = "order-123" → hash("order-123") % 3 → Partition 1
key = "order-456" → hash("order-456") % 3 → Partition 0
key = "order-123" → hash("order-123") % 3 → Partition 1  (같은 파티션!)
```

**같은 파티션 = 순서 보장**. 따라서 같은 주문 ID의 이벤트는 순서대로 처리된다.

### 3.3 Offset (오프셋)

파티션 내에서 각 메시지의 **위치 번호**. 소비자가 "어디까지 읽었는지" 추적한다.

```
Partition 0:
  Offset: 0     1     2     3     4     5     6
  Data:  [msg] [msg] [msg] [msg] [msg] [msg] [msg]
                            ↑
                    Consumer가 여기까지 읽음 (committed offset = 3)
                    다음에는 offset 4부터 읽음
```

- 소비자가 죽었다 살아나면 → **마지막 committed offset부터** 이어서 읽음
- 메시지를 다시 읽고 싶으면 → offset을 되돌릴 수 있음 (리플레이)

### 3.4 Consumer Group (소비자 그룹)

**같은 그룹** 내에서는 파티션을 나눠서 처리한다 (작업 분배).
**다른 그룹**은 독립적으로 모든 메시지를 받는다 (별도 구독).

```
Topic: flashsale.order.placed (파티션 3개)

┌─ Consumer Group: "payment-service" ─────────────────┐
│  Consumer 1 ← Partition 0                            │
│  Consumer 2 ← Partition 1, 2                         │
│  (같은 메시지를 중복 처리하지 않음)                     │
└──────────────────────────────────────────────────────┘

┌─ Consumer Group: "notification-service" ─────────────┐
│  Consumer 1 ← Partition 0, 1, 2                       │
│  (payment-service와 독립적으로 모든 메시지 수신)          │
└──────────────────────────────────────────────────────┘
```

### 3.5 Broker (브로커)

Kafka 서버 한 대. 여러 브로커가 모여 **클러스터**를 구성한다.

```
개발 환경: 브로커 1대
  [Broker 1] ── 모든 파티션 보유

운영 환경: 브로커 3대 (HA)
  [Broker 1] ── Partition 0 (Leader), Partition 1 (Replica)
  [Broker 2] ── Partition 1 (Leader), Partition 2 (Replica)
  [Broker 3] ── Partition 2 (Leader), Partition 0 (Replica)
```

### 3.6 DLQ (Dead Letter Queue)

처리에 **반복 실패한 메시지**를 별도 토픽에 보관한다. 나중에 원인 파악 후 재처리.

```
flashsale.order.placed
  ↓ Consumer 처리 시도
  ↓ 실패 → 재시도 3회
  ↓ 여전히 실패
  ↓
flashsale.order.placed.dlq    ← 여기에 보관 (수동 확인 후 재처리)
```

---

## 4. Producer (메시지 발행)

### 기본 흐름

```
애플리케이션 → Producer → 직렬화 → 파티셔닝 → Broker로 전송
```

### 핵심 설정

| 설정 | 값 | 의미 |
|------|-----|------|
| `key-serializer` | StringSerializer | 키를 바이트로 변환하는 방법 |
| `value-serializer` | StringSerializer | 값을 바이트로 변환하는 방법 |
| `acks` | `all` | 모든 복제본에 쓰기 완료 확인 (가장 안전) |

### acks 옵션

```
acks=0:  전송만 하고 확인 안 함      (가장 빠름, 유실 가능)
acks=1:  Leader만 확인              (보통)
acks=all: Leader + 모든 Replica 확인 (가장 안전, 이 프로젝트)
```

---

## 5. Consumer (메시지 소비)

### 기본 흐름

```
Broker → Consumer → 역직렬화 → 비즈니스 로직 → Offset 커밋
```

### 핵심 설정

| 설정 | 값 | 의미 |
|------|-----|------|
| `group-id` | 서비스명 | 소비자 그룹 ID |
| `auto-offset-reset` | `earliest` | 새 그룹일 때 처음부터 읽기 |
| `enable-auto-commit` | `true/false` | 오프셋 자동 커밋 여부 |

### auto-offset-reset

```
earliest: 토픽의 처음부터 읽기 (메시지 유실 방지)
latest:   지금부터 새로 들어오는 것만 읽기
none:     이전 offset이 없으면 에러
```

---

## 6. KRaft 모드

### ZooKeeper vs KRaft

예전 Kafka는 메타데이터 관리를 위해 별도의 **ZooKeeper** 서버가 필요했다.
Kafka 3.3+부터는 자체적으로 메타데이터를 관리하는 **KRaft** 모드를 지원한다.

```
예전: [ZooKeeper] + [Kafka Broker]   ← 서버 2종류 운영
지금: [Kafka (KRaft)]                ← 서버 1종류만 운영
```

### 이 프로젝트 설정

```yaml
KAFKA_PROCESS_ROLES: broker,controller    # 브로커 + 컨트롤러 겸용
KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk       # 클러스터 고유 ID
```

---

## 7. 이 프로젝트에서의 활용

### 토픽 구조

```
flashsale.order.placed              주문 생성됨
flashsale.order.cancelled           주문 취소됨 (보상 트랜잭션)
flashsale.order.completed           주문 완료됨
flashsale.payment.requested         결제 요청됨
flashsale.payment.completed         결제 완료됨
flashsale.payment.failed            결제 실패함
flashsale.stock.decremented         재고 차감됨
flashsale.stock.restored            재고 복원됨 (보상 트랜잭션)
flashsale.notification.send-requested  알림 전송 요청

각 토픽의 DLQ: {원본토픽}.dlq
```

**네이밍 규칙**: `flashsale.{도메인}.{이벤트}`

### 이벤트 흐름 (정상 시나리오)

```
사용자 주문 →
  1. order.placed           ← 주문 서비스 발행
  2. stock.decremented      ← 재고 서비스 발행
  3. payment.requested      ← 주문 서비스 발행
  4. payment.completed      ← 결제 서비스 발행
  5. order.completed        ← 주문 서비스 발행
  6. notification.send-requested  ← 알림 발행
```

### 이벤트 흐름 (실패 시나리오 — Saga 보상)

```
결제 실패 →
  1. payment.failed         ← 결제 서비스 발행
  2. stock.restored         ← 재고 서비스가 재고 복원
  3. order.cancelled        ← 주문 서비스가 주문 취소
```

### Docker 설정

```yaml
kafka:
  image: apache/kafka:3.8.1
  ports: ["9092:9092"]
  environment:
    KAFKA_NODE_ID: 1
    KAFKA_PROCESS_ROLES: broker,controller
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1  # 개발: 1, 운영: 3
```

### 프로젝트 파일

- `common/infrastructure/src/.../kafka/KafkaTopics.kt` — 토픽명 중앙 관리
- `common/infrastructure/src/.../kafka/` — Kafka 공통 설정

---

## 8. HA와 Replication

### 복제 (Replication)

각 파티션은 Leader 1개 + Replica N개로 구성된다.

```
Partition 0:
  [Broker 1: Leader]  →  [Broker 2: Replica]  →  [Broker 3: Replica]
                          (Leader와 동기화)         (Leader와 동기화)
```

- **Leader**: 모든 읽기/쓰기 처리
- **Replica**: Leader 데이터를 복제, Leader 장애 시 자동 승격

### 운영 환경 설정

```yaml
# docker-compose.ha.yml
KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 3      # 3개 복제본
KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 3
KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 2          # 최소 2개 동기화 필요
```

---

## 9. 자주 하는 실수 / 주의사항

### Consumer 수 > 파티션 수

```
# ❌ 파티션 3개, Consumer 5개 → 2개는 놀고 있음
Topic (3 partitions) → Consumer Group (5 consumers)
  Partition 0 → Consumer 1
  Partition 1 → Consumer 2
  Partition 2 → Consumer 3
  (idle)        Consumer 4    ← 할당받을 파티션 없음
  (idle)        Consumer 5    ← 할당받을 파티션 없음

# ✅ Consumer 수 ≤ 파티션 수
```

### 순서 보장 착각

```
# ❌ "Kafka는 순서를 보장한다" (부분적으로만 맞음)
# 파티션이 다르면 순서 보장 안 됨

# ✅ 순서가 필요한 메시지는 같은 키를 사용
kafkaTemplate.send(topic, orderId, event)   # orderId = 파티션 키
```

### 메시지 유실

```
# ❌ acks=0 → 전송 확인 안 함 → 유실 가능
acks: 0

# ✅ acks=all → 모든 복제본 확인 → 안전
acks: all
```

### 무한 재시도

```
# ❌ 메시지 처리 실패 → 무한 재시도 → 뒤의 메시지 처리 못함

# ✅ 최대 재시도 횟수 + DLQ
maxAttempts: 3 → 실패 시 DLQ로 이동
```

---

## 10. 정리 / 한눈에 보기

### 핵심 개념 요약

| 개념 | 비유 | 설명 |
|------|------|------|
| Topic | 우편함 종류 | 메시지 카테고리 |
| Partition | 우편함 칸 | 병렬 처리 단위 |
| Offset | 읽은 위치 표시 | 어디까지 읽었는지 |
| Consumer Group | 부서 | 같은 부서는 나눠서, 다른 부서는 각각 |
| Broker | 우체국 지점 | Kafka 서버 인스턴스 |
| DLQ | 반송 우편함 | 처리 실패 메시지 보관 |

### 이 프로젝트 설정 요약

| 항목 | 개발 환경 | 운영 환경 |
|------|---------|---------|
| 브로커 수 | 1 | 3 |
| Replication Factor | 1 | 3 |
| Min ISR | 1 | 2 |
| 모드 | KRaft | KRaft |

---

## 11. 더 알아보기

- [Kafka 공식 문서](https://kafka.apache.org/documentation/)
- [Kafka: The Definitive Guide (O'Reilly)](https://www.confluent.io/resources/kafka-the-definitive-guide/)
