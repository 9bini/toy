# Error Knowledge Base (에러 KB)

> 반복 발생하는 에러의 원인과 해결 패턴을 축적하는 지식 베이스.
> 새 에러 발견 시 해당 카테고리에 문서를 추가한다.

## 카테고리

### architecture/ — 아키텍처 관련
| ID | 제목 | 핵심 원인 |
|----|------|-----------|
| ARCH-001 | Hexagonal 의존성 역전 | adapter가 domain을 우회하고 직접 참조 |
| ARCH-002 | Port에 기술 세부사항 노출 | Port 인터페이스에 Redis/Kafka 타입 사용 |
| ARCH-003 | 순환 의존성 | 서비스 간 양방향 호출 |

### spring/ — Spring 프레임워크 관련
| ID | 제목 | 핵심 원인 |
|----|------|-----------|
| SPR-001 | Bean 순환 참조 | 생성자 주입 순환, @Lazy로 우회 시 설계 문제 |
| SPR-002 | @Async + Coroutine 충돌 | @Async 대신 코루틴 사용 필요 |
| SPR-003 | WebFlux blocking 호출 | suspend fun 내 blocking I/O 호출 |
| SPR-004 | R2DBC 트랜잭션 전파 | 코루틴 컨텍스트에서 트랜잭션 전파 안됨 |

### database/ — DB/캐시 관련
| ID | 제목 | 핵심 원인 |
|----|------|-----------|
| DB-001 | Redis 비원자적 연산 | GET → 비교 → SET 패턴 (Lua Script 필요) |
| DB-002 | Redis 연결 풀 고갈 | withTimeout 없이 대기, 풀 크기 부족 |
| DB-003 | Kafka Consumer 재처리 | 멱등성 미처리로 중복 실행 |

### kotlin/ — Kotlin 언어 관련
| ID | 제목 | 핵심 원인 |
|----|------|-----------|
| KT-001 | Null Safety 위반 | !! 사용, platform type 미처리 |
| KT-002 | Coroutine 구조 위반 | GlobalScope, fire-and-forget 패턴 |

### security/ — 보안 관련
| ID | 제목 | 핵심 원인 |
|----|------|-----------|
| SEC-001 | IDOR | 리소스 소유권 미확인 |
| SEC-002 | Race Condition 악용 | 재고 동시 차감 공격 |
| SEC-003 | Rate Limiting 우회 | 단일 식별자 의존 |

## 문서 템플릿

새 에러 KB 문서 작성 시 아래 형식을 따른다:

```markdown
# {ID}: {제목}

## 증상
- 어떤 에러 메시지/동작이 발생하는가

## 원인
- 근본 원인 설명

## 해결
- 단계별 해결 방법
- 코드 예시

## 예방
- 재발 방지를 위한 규칙/패턴

## 관련
- 관련 에러 KB 문서 링크
```
