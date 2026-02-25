---
name: design-service
description: 마이크로서비스를 DDD 기반으로 설계합니다. 도메인 모델, 포트/어댑터, API 스펙, 이벤트 설계를 포함합니다.
argument-hint: [service-name]
---

$ARGUMENTS 서비스를 DDD 기반으로 설계하세요.

## 설계 프로세스

### 1. 도메인 분석
- 핵심 도메인 엔티티, 밸류 오브젝트, 애그리거트 식별
- Bounded Context 경계 정의
- 도메인 이벤트 식별

### 2. 유스케이스 정의
- Application 레이어의 유스케이스 인터페이스 작성
- 각 유스케이스의 입력/출력 정의
- 비즈니스 규칙 명시

### 3. 포트/어댑터 설계
- Input Port: 유스케이스 인터페이스
- Output Port: 외부 시스템 인터페이스 (Redis, Kafka, DB)
- Web Adapter: WebFlux Controller/Router
- Infrastructure Adapter: Redis, Kafka, DB 구현체

### 4. API 스펙
- REST/SSE 엔드포인트 정의
- 요청/응답 DTO 스키마
- 에러 응답 코드 정의

### 5. 이벤트 설계
- Kafka 토픽명, 파티션 전략
- 메시지 스키마 (Avro/JSON)
- 발행/구독 관계도

### 6. 에러 처리 및 보상
- 실패 시나리오 목록
- 보상 트랜잭션 정의
- DLQ 전략

## 출력
- `docs/{service-name}/DESIGN.md`에 설계 문서 작성 (docs 디렉토리가 없으면 생성)
- 패키지 구조와 주요 클래스 목록
- Kafka 토픽 명세
- 시퀀스 다이어그램 (Mermaid 형식)
