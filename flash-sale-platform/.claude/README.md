# .claude/ 구조

Flash Sale Platform의 Claude Code 설정 디렉토리.

```
.claude/
├── settings.json              # 팀 공유 설정 (권한, hooks)
├── README.md                  # 이 파일
│
├── rules/                     # 규칙 (Claude에 항상 로드)
│   ├── 01-agent-behavior.md   #   에이전트 행동 규칙, Intent Lock
│   ├── 02-architecture.md     #   Hexagonal Architecture
│   ├── 04-data-integration.md #   Redis/Kafka/DB 패턴
│   ├── 05-api-design.md       #   REST API + WebFlux 설계
│   ├── 06-security-patterns.md#   인증/인가/IDOR/Race Condition
│   ├── 07-error-handling.md   #   sealed class 에러 처리
│   ├── 10-redteam.md          #   자동 보안 리뷰 (10카테고리)
│   └── 11-rubric-scoring.md   #   5차원 품질 채점 (D1~D5)
│
├── agents/                    # 서브에이전트 (10개)
│   ├── architect.md           #   아키텍처 설계
│   ├── code-reviewer.md       #   코드 가독성 리뷰
│   ├── devops-engineer.md     #   인프라/배포
│   ├── doc-writer.md          #   문서 작성
│   ├── integration-tester.md  #   통합 테스트
│   ├── kotlin-expert.md       #   Kotlin 전문가
│   ├── performance-engineer.md#   성능 최적화
│   ├── project-manager.md     #   프로젝트 관리
│   ├── security-reviewer.md   #   보안 리뷰
│   └── test-engineer.md       #   테스트 설계
│
├── skills/                    # 스킬 (13개)
│   ├── full-feature/          #   /full-feature - 전체 파이프라인
│   ├── hotfix/                #   /hotfix - 빠른 버그 수정
│   ├── implement-api/         #   /implement-api - API 구현
│   ├── write-test/            #   /write-test - 테스트 작성
│   ├── design-service/        #   /design-service - DDD 설계
│   ├── review-code/           #   /review-code - 코드 리뷰
│   ├── debug-issue/           #   /debug-issue - 디버깅
│   ├── redis-setup/           #   /redis-setup - Redis 설정
│   ├── kafka-setup/           #   /kafka-setup - Kafka 설정
│   ├── saga-pattern/          #   /saga-pattern - 분산 트랜잭션
│   ├── check-all/             #   /check-all - 전체 품질 검사
│   ├── document/              #   /document - 문서 작성
│   └── performance-test/      #   /performance-test - 부하 테스트
│
├── hooks/                     # 자동화 hooks
│   ├── session-start.sh       #   세션 시작 시 환경 확인
│   ├── pre-commit.sh          #   커밋 전 빌드 검증
│   └── post-code-quality.sh   #   코드 작성 후 품질 체크
│
├── redteam/                   # RedTeam 보안 리뷰
│   └── suppressions.json      #   오탐 억제 목록
│
├── rubric/                    # 품질 채점
│   └── cases/                 #   채점 예시 (추가 예정)
│
└── docs/error/                # 에러 지식 베이스
    ├── INDEX.md               #   루트 인덱스
    ├── architecture/          #   아키텍처 관련 에러
    ├── spring/                #   Spring 프레임워크 에러
    ├── database/              #   DB/캐시 에러
    ├── kotlin/                #   Kotlin 언어 에러
    └── security/              #   보안 관련 에러
```

## 번호 체계 (rules/)

| 번호 범위 | 영역 |
|-----------|------|
| 01-03 | 에이전트 행동 + 아키텍처 |
| 04-05 | 데이터 연동 + API 설계 |
| 06-09 | 보안 + 에러 처리 |
| 10-12 | 자동화 (RedTeam, Rubric) |

## git 미추적 대상

| 경로 | 설명 |
|------|------|
| `agent-memory/` | 세션 간 에이전트 학습 기록 |
| `worktrees/` | 임시 git worktree |
