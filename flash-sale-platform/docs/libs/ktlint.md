# ktlint

---

## 목차

1. [이것이 뭔가?](#1-이것이-뭔가)
2. [왜 필요한가?](#2-왜-필요한가)
3. [핵심 규칙](#3-핵심-규칙)
4. [기본 사용법](#4-기본-사용법)
5. [이 프로젝트에서의 활용](#5-이-프로젝트에서의-활용)
6. [자주 하는 실수 / 주의사항](#6-자주-하는-실수--주의사항)
7. [정리 / 한눈에 보기](#7-정리--한눈에-보기)
8. [더 알아보기](#8-더-알아보기)

---

## 1. 이것이 뭔가?

### 한 줄 요약

Kotlin 코드가 **공식 코딩 스타일 가이드**를 따르는지 자동으로 검사하고 수정하는 린터(Linter).

### 비유: 맞춤법 검사기

- 한글 맞춤법 검사기처럼, 코드의 "맞춤법(스타일)"을 검사
- 틀린 부분을 알려주고, 자동으로 고쳐주기도 함

---

## 2. 왜 필요한가?

### 코드 스타일이 중요한 이유

```kotlin
// 사람 A의 스타일
fun getData():String{
    return repo.findAll()
        .filter{it.status=="ACTIVE"}}

// 사람 B의 스타일
fun getData(): String {
    return repo.findAll()
        .filter { it.status == "ACTIVE" }
}
```

코드 리뷰에서 로직이 아닌 **스타일 논쟁**에 시간 낭비 → ktlint가 자동으로 통일

---

## 3. 핵심 규칙

### 들여쓰기

```kotlin
// ❌ 탭
fun process() {
→   val x = 1
}

// ✅ 4칸 스페이스
fun process() {
    val x = 1
}
```

### import 정렬

```kotlin
// ❌ 정렬 안 됨
import java.util.List
import com.example.Foo
import java.io.File

// ✅ 알파벳 순 정렬
import com.example.Foo
import java.io.File
import java.util.List
```

### 불필요한 세미콜론

```kotlin
// ❌
val x = 1;

// ✅
val x = 1
```

### 공백 규칙

```kotlin
// ❌ 콜론 앞뒤 공백 불일치
fun getData():String {
    if(condition){
        val map = mapOf("key" to"value")
    }
}

// ✅ 일관된 공백
fun getData(): String {
    if (condition) {
        val map = mapOf("key" to "value")
    }
}
```

### 줄 끝 공백

```kotlin
// ❌ 줄 끝에 보이지 않는 공백
val x = 1

// ✅ 줄 끝 공백 없음
val x = 1
```

---

## 4. 기본 사용법

### Gradle 명령어

```bash
# 검사만 (위반 사항 보고)
./gradlew ktlintCheck

# 자동 수정
./gradlew ktlintFormat

# 특정 서비스만
./gradlew :services:order-service:ktlintCheck
```

### 출력 예시

```
> Task :services:order-service:ktlintMainSourceSetCheck FAILED

src/main/kotlin/com/flashsale/order/OrderService.kt:15:1:
  Unexpected indentation (expected 4 but was 3) (standard:indent)

src/main/kotlin/com/flashsale/order/OrderController.kt:8:1:
  Imports must be ordered in lexicographic order without any empty lines in-between (standard:import-ordering)
```

---

## 5. 이 프로젝트에서의 활용

### Gradle 플러그인 설정

```kotlin
// build.gradle.kts (루트)
plugins {
    alias(libs.plugins.ktlint) apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
```

```properties
# gradle.properties
kotlin.code.style=official
```

### CI에서 활용

PR 올리면 CI에서 `ktlintCheck` 실행 → 위반 시 빌드 실패 → 머지 불가

---

## 6. 자주 하는 실수 / 주의사항

### ktlintFormat이 못 고치는 경우

일부 복잡한 위반은 수동으로 고쳐야 한다.

### IDE 설정과 충돌

```
IDE의 코드 포매터 설정이 ktlint와 다르면:
  → IDE에서 포매팅 → ktlint 위반 → 다시 수정

해결: IDE에 ktlint 스타일 적용
  IntelliJ: Settings → Editor → Code Style → Kotlin → Set from... → Kotlin Style Guide
```

---

## 7. 정리 / 한눈에 보기

| 명령어 | 동작 |
|--------|------|
| `./gradlew ktlintCheck` | 스타일 검사 |
| `./gradlew ktlintFormat` | 자동 수정 |

| 주요 규칙 | 내용 |
|----------|------|
| 들여쓰기 | 4칸 스페이스 |
| import | 알파벳 순 |
| 세미콜론 | 불필요 시 제거 |
| 공백 | Kotlin 공식 가이드 |

---

## 8. 더 알아보기

- [ktlint 공식 GitHub](https://github.com/pinterest/ktlint)
- [Kotlin 코딩 컨벤션](https://kotlinlang.org/docs/coding-conventions.html)
- [ktlint Gradle 플러그인](https://github.com/JLLeitschuh/ktlint-gradle)
