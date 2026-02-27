---
name: check-all
description: 프로젝트 전체 품질 검사를 실행합니다. 린트, 컴파일, 테스트, 아키텍처 규칙을 한 번에 확인합니다.
argument-hint: [--fix]
---

전체 품질 검사를 실행하세요. $ARGUMENTS

## 검사 항목 (순서대로 실행)

### 1. 코드 포맷 검사
```bash
# --fix 옵션이 있으면 자동 포맷
./gradlew ktlintFormat  # --fix 시
./gradlew ktlintCheck   # --fix 없을 시
```

### 2. 컴파일 검사
```bash
./gradlew compileKotlin
```

### 3. 전체 테스트
```bash
./gradlew test
```

### 4. 아키텍처 규칙 검사 (Grep으로 수동)
다음 위반 사항을 검색합니다:
- adapter 패키지에서 다른 서비스의 domain 직접 import
- domain 패키지에서 adapter/application 패키지 import (의존성 역전)
- GlobalScope 사용
- runBlocking 사용 (테스트 제외)
- withTimeout 없는 외부 호출
- 네트워크 호출(HTTP, Kafka)에 @Retryable 미적용
- deprecated API 사용 (Spring Boot/Kotlin/라이브러리 최신 버전 기준)

### 5. 결과 요약
검사 결과를 아래 표로 정리합니다:

| 항목 | 상태 | 세부 |
|------|------|------|
| ktlint | PASS/FAIL | 위반 N건 |
| 컴파일 | PASS/FAIL | 에러 N건 |
| 테스트 | PASS/FAIL | N개 중 M개 통과 |
| 아키텍처 | PASS/WARN | 위반 N건 |

### 6. 수정 제안
FAIL/WARN 항목에 대해 구체적인 수정 방법을 제안합니다.
