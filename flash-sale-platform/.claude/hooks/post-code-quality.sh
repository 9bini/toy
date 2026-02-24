#!/bin/bash
set -euo pipefail

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')

# .kt 파일만 대상
if [[ -z "$FILE_PATH" || "$FILE_PATH" != *.kt ]]; then
  exit 0
fi

if [[ ! -f "$FILE_PATH" ]]; then
  exit 0
fi

WARNINGS=""

# 1. 50줄 초과 함수 검사
LONG_FUNCTIONS=$(awk '
  /^\s*(override\s+)?(suspend\s+)?fun\s/ { start=NR; name=$0 }
  start && /^\s*\}/ {
    len=NR-start;
    if(len>50) printf "  [경고] %d줄 함수 (라인 %d-%d)\n", len, start, NR
    start=0
  }
' "$FILE_PATH" 2>/dev/null || true)

if [ -n "$LONG_FUNCTIONS" ]; then
  WARNINGS="${WARNINGS}[함수 길이] 50줄 초과:\n${LONG_FUNCTIONS}\n"
fi

# 2. GlobalScope 사용 검사
if grep -qn "GlobalScope" "$FILE_PATH" 2>/dev/null; then
  WARNINGS="${WARNINGS}[코루틴] GlobalScope 사용 감지 - Structured Concurrency 위반\n"
fi

# 3. runBlocking 검사 (테스트 코드 제외)
if [[ "$FILE_PATH" != *Test* && "$FILE_PATH" != *test* ]]; then
  if grep -qn "runBlocking" "$FILE_PATH" 2>/dev/null; then
    WARNINGS="${WARNINGS}[코루틴] runBlocking 사용 감지 - suspend fun 사용 권장\n"
  fi
fi

# 4. withTimeout 없는 외부 호출 검사
if grep -qn "redisTemplate\|reactiveRedisTemplate\|kafkaTemplate\|webClient\|r2dbcEntityTemplate" "$FILE_PATH" 2>/dev/null; then
  if ! grep -qn "withTimeout" "$FILE_PATH" 2>/dev/null; then
    WARNINGS="${WARNINGS}[타임아웃] 외부 호출(Redis/Kafka/WebClient/DB)이 감지되었으나 withTimeout이 없습니다\n"
  fi
fi

# 5. 중첩 깊이 검사 (라인별 열린 중괄호 누적 4단계 이상)
DEEP_NESTING=$(awk '
  {
    for(i=1; i<=length($0); i++) {
      c = substr($0,i,1)
      if(c == "{") depth++
      if(c == "}") depth--
    }
    if(depth >= 4) printf "  [경고] 라인 %d: 중첩 깊이 %d단계\n", NR, depth
  }
' "$FILE_PATH" 2>/dev/null || true)

if [ -n "$DEEP_NESTING" ]; then
  WARNINGS="${WARNINGS}[중첩] 깊은 중첩 감지:\n${DEEP_NESTING}\n"
fi

# 6. Magic Number 검사 (timeout/delay/retry에 하드코딩된 숫자)
MAGIC_NUMBERS=$(grep -nE '(withTimeout|delay|retry|Duration\.of)\s*\(\s*[0-9]+' "$FILE_PATH" 2>/dev/null | \
  grep -v "companion\|const\|val \|private val\|object " || true)

if [ -n "$MAGIC_NUMBERS" ]; then
  WARNINGS="${WARNINGS}[매직넘버] 타임아웃/딜레이에 하드코딩된 숫자 - 상수로 정의 필요:\n${MAGIC_NUMBERS}\n"
fi

if [ -n "$WARNINGS" ]; then
  echo "=== 코드 품질 경고 ==="
  echo -e "$WARNINGS"
fi

exit 0
