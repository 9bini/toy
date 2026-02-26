# GitHub 무료 기능 활용 가이드 (Public Repository)

Public 레포에서 무료로 사용 가능한 GitHub 기능 정리.
코드로 설정된 것과 UI에서 수동으로 켜야 하는 것을 구분한다.

---

## Part 1: 코드로 설정된 워크플로우

### CI/CD

| 워크플로우 | 파일 | 트리거 | 역할 |
|-----------|------|--------|------|
| **CI** | `workflows/ci.yml` | 모든 브랜치 push + main PR | 빌드·린트·테스트 실행 |
| **Docker Build** | `workflows/docker.yml` | `v*` 태그 push | 5개 서비스 빌드 → GHCR push |
| **Deploy** | `workflows/deploy.yml` | 수동 트리거 | 환경별(staging/prod) 배포 |

### 보안

| 워크플로우 | 파일 | 트리거 | 역할 |
|-----------|------|--------|------|
| **CodeQL** | `workflows/codeql.yml` | `.kt` 변경 + 매주 월요일 | Kotlin 보안 취약점 정적 분석 |
| **Dependency Review** | `workflows/dependency-review.yml` | main PR | 취약 의존성/GPL 라이선스 차단 |
| **OSSF Scorecard** | `workflows/scorecard.yml` | main push + 매주 화요일 | 보안 모범사례 점수 측정 |

### 자동화

| 워크플로우 | 파일 | 트리거 | 역할 |
|-----------|------|--------|------|
| **Dependabot** | `dependabot.yml` | 매주 월요일 | Gradle/Actions 의존성 업데이트 PR |
| **PR Labeler** | `workflows/labeler.yml` | PR 생성/동기화 | 변경 경로 기반 자동 라벨 |
| **Release Drafter** | `workflows/release-drafter.yml` | main push + PR | 라벨 기반 릴리스 노트 자동 생성 |
| **Stale bot** | `workflows/stale.yml` | 매일 | 비활성 이슈/PR 자동 정리 |

### 워크플로우 상세

#### CI (`ci.yml`)
```
push (모든 브랜치) → 빌드+테스트
PR (→ main) → 머지 가능 여부 확인
```
- JDK 21 + Gradle로 빌드·린트(`ktlint`)·테스트 일괄 실행
- **concurrency**: 같은 PR에서 새 push → 이전 실행 자동 취소
- 테스트 실패 시 리포트를 아티팩트로 7일간 보관

#### CodeQL (`codeql.yml`)
- GitHub의 정적 분석 엔진으로 SQL injection, XSS 등 보안 취약점 검출
- 결과: **Security 탭 > Code scanning alerts**

#### Dependency Review (`dependency-review.yml`)
- PR에 포함된 의존성 변경을 분석
- high/critical 취약점 → PR 차단
- GPL 라이선스 → PR 차단
- PR 코멘트로 리포트 요약

#### OSSF Scorecard (`scorecard.yml`)
- OpenSSF 기준 보안 점수: branch protection, CI 설정, 취약점 관리 등 평가
- Security 탭에 SARIF 업로드
- scorecard.dev에서 공개 점수 조회 가능

#### Dependabot (`dependabot.yml`)
- Spring Boot / Kotlin / Testing 라이브러리를 그룹별로 묶어 PR 1개로 생성
- GitHub Actions 버전도 자동 업데이트

#### PR Labeler (`labeler.yml` + `workflows/labeler.yml`)
- 변경 파일 경로 → 라벨 자동 매핑
  - `.github/**` → `infra`
  - `services/gateway/**` → `service/gateway`
  - `**/*Test.kt` → `tests`
  - `**/*.md` → `docs`
- Release Drafter와 연동되어 릴리스 노트 분류에 활용

#### Release Drafter (`release-drafter.yml`)
- PR 라벨 기반 릴리스 노트 초안 자동 작성
  - `feature`/`enhancement` → 🚀 New Features (minor)
  - `bug`/`fix` → 🐛 Bug Fixes (patch)
  - `infra`/`dependencies` → 🏗️ Infrastructure (patch)
- Releases 탭에서 Draft 확인 → Publish

#### Docker Build (`docker.yml`)
```
git tag v1.0.0 && git push --tags
```
- 5개 서비스 매트릭스 병렬 빌드
- GHCR(GitHub Container Registry)에 `version` + `latest` 태그로 push
- BuildKit 캐시로 레이어 재사용

#### Deploy (`deploy.yml`)
- Actions 탭 > Run workflow에서 수동 실행
- 입력: 환경(staging/production), 서비스(개별/전체), 버전
- 현재 플레이스홀더 — 실제 인프라에 맞게 커스터마이즈 필요

#### Stale bot (`stale.yml`)
- 비활성 이슈(30일) / PR(14일) → `stale` 라벨 → 7일 후 자동 close
- `pinned`, `keep` 라벨이 있으면 예외

---

## Part 2: GitHub UI에서 수동 설정 필요

### 보안 (Settings > Code security and analysis)

| 기능 | 설명 | 권장 |
|------|------|:----:|
| **Dependency graph** | 의존성 시각화, Dependabot의 기반 | ON |
| **Dependabot alerts** | CVE 발견 시 자동 알림 | ON |
| **Dependabot security updates** | 취약 의존성 자동 패치 PR | ON |
| **Secret scanning** | 커밋에서 API 키/토큰 노출 감지 | ON |
| **Push protection** | 시크릿 포함 push 사전 차단 | ON |

### 브랜치 보호 (Settings > Branches > Add rule)

`main` 브랜치:

| 설정 | 효과 |
|------|------|
| Require a pull request before merging | main 직접 push 방지 |
| Require approvals (1명) | 코드 리뷰 필수 |
| Require status checks to pass | CI 통과해야 머지 가능 |
| → `Flash Sale Platform CI` 선택 | 빌드+테스트 필수 |
| → `dependency-review` 선택 | 취약 의존성 차단 |
| Require conversation resolution | PR 코멘트 해결 필수 |

### 태그 보호 (Settings > Tags > Add rule)

| 패턴 | 효과 |
|------|------|
| `v*` | 릴리스 태그 삭제/덮어쓰기 방지 |

### Environment (Settings > Environments)

**production:**
| 설정 | 효과 |
|------|------|
| Required reviewers | 배포 전 승인 필요 |
| Wait timer (5분) | 배포 전 대기 (실수 방지) |
| Deployment branches → main only | main에서만 배포 허용 |

**staging:**
- 보호 없이 자유롭게 배포

### 기타

| 기능 | 위치 | 설명 |
|------|------|------|
| **GitHub Pages** | Settings > Pages | 정적 사이트 호스팅 (문서, 테스트 리포트) |
| **GitHub Discussions** | Settings > Features | Q&A/토론 게시판 |
| **GitHub Projects** | Projects 탭 | 칸반 보드 프로젝트 관리 |
| **Insights** | Insights 탭 | 커밋 빈도, 트래픽 통계 |
