---
name: hotfix
description: 빠른 버그 수정 워크플로우. 원인 분석 → 최소 변경 수정 → 회귀 테스트 추가 → PR 생성을 신속하게 수행합니다.
argument-hint: [error-description-or-log]
---

$ARGUMENTS 버그를 핫픽스하세요.

## 프로세스

### 1. 원인 분석
- 에러 로그/스택트레이스에서 핵심 정보 추출
- 관련 코드 빠르게 탐색 (Grep + Read)
- 근본 원인 식별 (증상이 아닌 원인)
- 영향 범위 파악

### 2. 최소 변경 수정
- 영향 범위를 최소화하는 수정만 적용
- 관련 없는 리팩토링 금지
- 사이드 이펙트 확인
- 수정 코드에 한국어 주석으로 "왜 이렇게 수정했는지" 설명

### 3. 회귀 테스트 추가
- 이 버그를 정확히 재현하는 테스트 작성
- 수정 전에는 실패, 수정 후에는 통과하는 테스트
- 기존 테스트 전체 실행하여 통과 확인:
  ```bash
  ./gradlew :services:{service}:test
  ```

### 4. 검증
```bash
./gradlew :services:{service}:ktlintFormat
./gradlew :services:{service}:build
```

### 5. PR 생성
- 브랜치: `hotfix/{service-name}/{bug-description}`
- 커밋: `fix({service}): {한국어 설명}`
- PR 본문: 원인, 수정 내용, 재현 테스트, 영향 범위 포함

### 6. 변경사항 요약
글로벌 CLAUDE.md 템플릿에 따라 요약 작성
