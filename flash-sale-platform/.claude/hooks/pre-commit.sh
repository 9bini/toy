#!/bin/bash
set -euo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
WARNINGS=""
ERRORS=""

# 1. ktlint 자동 포맷
echo "=== ktlint 자동 포맷 ==="
cd "$PROJECT_DIR"
if ./gradlew ktlintFormat --quiet 2>/dev/null; then
  echo "ktlint 포맷 완료"
else
  ERRORS="${ERRORS}[ktlint] 포맷 실패 - 수동 확인 필요\n"
fi

# 2. 시크릿 하드코딩 검사 (소스 코드에서만, 테스트/설정 제외)
SECRETS=$(grep -rn "password\s*=\s*\"[^\"]\+\"\|secret\s*=\s*\"[^\"]\+\"\|apiKey\s*=\s*\"[^\"]\+\"" \
  --include="*.kt" \
  "$PROJECT_DIR/services/" "$PROJECT_DIR/common/" 2>/dev/null | \
  grep -iv "test\|Test\|mock\|Mock\|TODO\|FIXME\|interface\|abstract\|companion\|application.*yml" || true)

if [ -n "$SECRETS" ]; then
  WARNINGS="${WARNINGS}[보안] 하드코딩된 시크릿 의심:\n${SECRETS}\n"
fi

# 3. TODO/FIXME 잔여 알림
TODOS=$(grep -rn "TODO\|FIXME\|HACK\|XXX" --include="*.kt" \
  "$PROJECT_DIR/services/" "$PROJECT_DIR/common/" 2>/dev/null | head -10 || true)

if [ -n "$TODOS" ]; then
  WARNINGS="${WARNINGS}[알림] 미해결 TODO/FIXME:\n${TODOS}\n"
fi

# 결과 출력
if [ -n "$ERRORS" ]; then
  echo "=== Pre-commit 에러 ==="
  echo -e "$ERRORS"
fi

if [ -n "$WARNINGS" ]; then
  echo "=== Pre-commit 경고 ==="
  echo -e "$WARNINGS"
fi

# 에러가 있으면 실패, 경고만 있으면 통과
if [ -n "$ERRORS" ]; then
  exit 1
fi

exit 0
