#!/bin/bash
set -euo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"

# Gradle wrapper 실행 권한 설정
if [ -f "$PROJECT_DIR/gradlew" ]; then
  chmod +x "$PROJECT_DIR/gradlew"
fi

# Docker Compose 인프라 상태 확인
if command -v docker &> /dev/null; then
  if [ -f "$PROJECT_DIR/docker-compose.yml" ]; then
    echo "=== Docker 인프라 상태 ==="
    docker compose -f "$PROJECT_DIR/docker-compose.yml" ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null || echo "Docker compose 서비스 없음 (docker compose up -d 로 시작하세요)"
  fi
fi

# Java 버전 확인
if command -v java &> /dev/null; then
  JAVA_VERSION=$(java -version 2>&1 | head -n 1)
  echo "Java: $JAVA_VERSION"
fi

# Memory 시스템 상태 안내
MEMORY_DIR="$HOME/.claude/projects/-Users-taekyun-Documents-IdeaProjects-toy/memory"
if [ -d "$MEMORY_DIR" ] && [ -f "$MEMORY_DIR/MEMORY.md" ]; then
  echo "=== 프로젝트 메모리 로드됨 ==="
  MEMORY_FILES=$(ls "$MEMORY_DIR"/*.md 2>/dev/null | xargs -I {} basename {} | tr '\n' ', ' | sed 's/,$//')
  echo "파일: $MEMORY_FILES"
else
  echo "=== 프로젝트 메모리 미설정 ==="
fi

# 최근 git 변경사항 요약
echo "=== 최근 커밋 (5개) ==="
git -C "$PROJECT_DIR" log --oneline -5 2>/dev/null || echo "git 로그 없음"
