---
name: full-feature
description: 기능을 설계부터 PR까지 원스톱으로 구현합니다. 설계 → 구현 → 테스트 → 리뷰 → PR 생성의 전체 파이프라인을 실행합니다.
argument-hint: [service-name] [feature-description]
---

$ARGUMENTS 기능을 전체 파이프라인으로 구현하세요.

## 전체 파이프라인 (6단계)

### 1단계: 설계 확인
- 도메인 모델 설계 (Entity, Value Object, Error)
- UseCase 정의 (Input/Output Port)
- Adapter 식별 (Redis/Kafka/DB 중 필요한 것)
- API 스펙 (요청/응답 DTO, HTTP 메서드, 경로)
- Kafka 이벤트 설계 (필요한 경우)
- Redis 키/연산 설계 (필요한 경우)
- 사용자에게 설계 확인 요청 후 진행

### 2단계: 구현 (구현 순서 엄수)
아래 순서를 반드시 따릅니다:
1. **Domain**: Entity, Value Object, sealed interface Error (KDoc 포함)
2. **Port Out**: Output Port 인터페이스 (기술 세부사항 노출 금지)
3. **Port In**: UseCase 인터페이스
4. **UseCase Implementation**: 비즈니스 로직 (suspend fun, withTimeout)
5. **Adapter Out**: Redis/Kafka/DB 구현체 (클래스명에 기술 스택 포함)
6. **Adapter In**: Controller (suspend fun, WebFlux)
7. **Config**: Spring 빈 등록

### 3단계: 테스트 작성 + 실행
- UseCase 단위 테스트 (Kotest BehaviorSpec + MockK)
  - 정상 케이스, 에러 케이스, 엣지 케이스
  - coEvery/coVerify 사용
- Controller 테스트 (WebTestClient, 필요시)
- 통합 테스트 (Testcontainers, Redis/Kafka 사용 시)
- 모든 테스트 실행: `./gradlew :services:{service}:test`
- 실패 시 즉시 수정 후 재실행

### 4단계: 품질 검증
```bash
./gradlew :services:{service}:ktlintFormat
./gradlew :services:{service}:build
```
- 빌드 실패 시 즉시 수정

### 5단계: 변경사항 요약
글로벌 CLAUDE.md의 변경사항 요약 템플릿에 따라 작성:
- 무엇을 했는가 (1줄)
- 왜 이렇게 했는가 (핵심 결정)
- 변경된 파일 테이블
- 핵심 코드 흐름
- 주의사항

### 6단계: PR 생성
- 브랜치: `feature/{service-name}/{feature-description}`
- 커밋: `feat({service}): {한국어 설명}`
- PR 제목: 70자 이내
- PR 본문: 설계 결정, 변경 파일, 테스트 결과

## 중단 조건
- 빌드 실패 → 즉시 수정
- 테스트 실패 → 즉시 수정
- ktlint 위반 → 자동 포맷
