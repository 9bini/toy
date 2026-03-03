# 12. 프로젝트 세팅 및 환경 구성

> **한 줄 요약**: Gradle 멀티모듈 + Version Catalog + Spring Boot 4.0 + Kotest 6.x 기반 프로젝트 설정과 해결한 환경 이슈들

---

## 프로젝트 구조

```
flash-sale-platform/
├── build.gradle.kts              # 루트: 공통 플러그인, 전체 서브프로젝트 설정
├── settings.gradle.kts           # 모듈 등록
├── gradle/libs.versions.toml     # 의존성 버전 중앙 관리 (Version Catalog)
├── common/
│   ├── domain/                   # 순수 도메인: Result, DomainEvent, IdGenerator
│   └── infrastructure/           # 공유 인프라: Redis, Kafka, 로깅, 테스트 베이스
└── services/
    ├── gateway/                  # API Gateway (Nginx 프록시)
    ├── queue-service/            # 대기열 서비스 (Redis Sorted Set)
    ├── order-service/            # 주문 서비스 (Saga 패턴)
    ├── payment-service/          # 결제 서비스
    └── notification-service/     # 알림 서비스 (SSE/이메일)
```

---

## Gradle Version Catalog (libs.versions.toml)

### 왜 Version Catalog를 쓰는가?

```kotlin
// ❌ 기존 방식: 버전이 여러 build.gradle에 흩어짐
dependencies {
    implementation("io.kotest:kotest-runner-junit5:6.1.4")
    implementation("io.kotest:kotest-assertions-core:6.1.4")  // 버전 불일치 위험
}

// ✅ Version Catalog: gradle/libs.versions.toml에서 한 곳에 관리
dependencies {
    implementation(libs.kotest.runner.junit5)   // 버전은 toml에서 일괄 관리
    implementation(libs.kotest.assertions.core)
}
```

### 핵심 의존성 버전

| 카테고리 | 라이브러리 | 버전 | 용도 |
|---------|-----------|------|------|
| 언어 | Kotlin | 2.3.10 | JVM 21 타겟 |
| 프레임워크 | Spring Boot | 4.0.3 | WebFlux + Coroutines |
| 코루틴 | kotlinx-coroutines | 1.10.2 | 비동기 처리 |
| 테스트 | Kotest | 6.1.4 | BDD 스타일 테스트 |
| 테스트 | MockK | 1.14.9 | Kotlin 전용 모킹 |
| 테스트 | Testcontainers | 2.0.3 | 통합 테스트용 컨테이너 |
| 로깅 | kotlin-logging | 7.0.13 | 구조적 로깅 |
| Redis | Spring Data Redis Reactive | (Boot 관리) | Lettuce 기반 비동기 Redis |
| Redis | Redisson | 4.2.0 | 분산 락 |

---

## 멀티모듈 의존성 구조

```
services/queue-service
    └── implementation(project(":common:infrastructure"))
            └── api(project(":common:domain"))      ← transitive
            └── api("spring-boot-starter-webflux")   ← transitive
            └── api("spring-boot-starter-data-redis-reactive")  ← transitive
```

### api vs implementation

| | `api` | `implementation` |
|---|---|---|
| transitive | 의존하는 모듈에 노출 | 내부에서만 사용 |
| 사용 시점 | 공개 API에 노출되는 의존성 | 내부 구현 세부사항 |
| 예시 | WebFlux (서비스에서 직접 사용) | Redisson (common에서만 래핑) |

```kotlin
// common/infrastructure/build.gradle.kts
dependencies {
    api(project(":common:domain"))                              // 서비스에서 Result, DomainEvent 사용
    api("org.springframework.boot:spring-boot-starter-webflux") // 서비스에서 Controller 작성
    api("org.springframework.boot:spring-boot-starter-data-redis-reactive") // 서비스에서 Redis 사용

    implementation(libs.redisson.spring.boot.starter)           // 분산 락은 common에서만 래핑
}
```

---

## 루트 build.gradle.kts 구성

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false         // 서브프로젝트에서 적용
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "com.flashsale"
    version = "0.0.1-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    // Kotlin JVM 21 타겟
    configure<KotlinJvmProjectExtension> { jvmToolchain(21) }

    // strict null 검사
    tasks.withType<KotlinCompile> {
        compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
    }

    // JUnit Platform (Kotest 호환)
    tasks.withType<Test> { useJUnitPlatform() }

    dependencies {
        // 모든 서브프로젝트 공통
        "implementation"(rootProject.libs.bundles.coroutines)
        "implementation"(rootProject.libs.kotlin.logging)
        "testImplementation"(rootProject.libs.bundles.kotest)
        "testImplementation"(rootProject.libs.kotest.extensions.spring)
        "testImplementation"(rootProject.libs.mockk)
        "testImplementation"(rootProject.libs.coroutines.test)
    }

    // 서비스 모듈 전용 의존성
    if (project.path.startsWith(":services:")) {
        dependencies {
            "implementation"("org.springframework.boot:spring-boot-starter-actuator")
            "implementation"("io.projectreactor.kotlin:reactor-kotlin-extensions")
            "implementation"("io.micrometer:micrometer-registry-prometheus")
            "testImplementation"("org.springframework.boot:spring-boot-starter-test")
            "testImplementation"("io.projectreactor:reactor-test")
        }
    }
}
```

### `-Xjsr305=strict` 의미

Java 라이브러리의 `@Nullable` / `@NonNull` 어노테이션을 Kotlin 컴파일러가 엄격하게 처리:

```kotlin
// Spring의 Java 메서드가 @Nullable을 반환하면
// Kotlin에서 자동으로 `T?`로 추론
val rank: Long? = redisTemplate.opsForZSet().rank(key, userId).awaitFirstOrNull()
//                                                             ↑ nullable 반환
```

---

## Spring Boot AutoConfiguration

### 문제: 패키지가 다른 공통 빈을 어떻게 스캔하나?

```
com.flashsale.queue      ← @SpringBootApplication (queue-service)
com.flashsale.common     ← TimeoutProperties, ErrorResponse 등 (common 모듈)
```

`@SpringBootApplication`은 자신의 패키지(`com.flashsale.queue`)만 스캔하므로, `com.flashsale.common` 패키지의 빈은 자동으로 등록되지 않습니다.

### 해결: AutoConfiguration

```kotlin
// common/infrastructure/.../FlashSaleCommonAutoConfiguration.kt
@AutoConfiguration
@ComponentScan(basePackages = ["com.flashsale.common"])
@EnableConfigurationProperties(TimeoutProperties::class)
class FlashSaleCommonAutoConfiguration
```

```
// META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.flashsale.common.config.FlashSaleCommonAutoConfiguration
```

**동작 흐름:**
1. Spring Boot 시작 시 classpath의 `AutoConfiguration.imports` 파일을 스캔
2. `FlashSaleCommonAutoConfiguration` 클래스를 로드
3. `@ComponentScan`이 `com.flashsale.common` 패키지의 `@Component`, `@Service` 등을 등록
4. `@EnableConfigurationProperties`가 `TimeoutProperties`를 빈으로 등록

---

## testFixtures — 테스트 공통 코드 공유

### 왜 testFixtures인가?

```
// 일반 test 소스셋 → 다른 모듈에서 접근 불가
common/infrastructure/src/test/  ← queue-service에서 사용 불가

// testFixtures → 다른 모듈의 test에서 접근 가능
common/infrastructure/src/testFixtures/  ← queue-service에서 사용 가능
```

```kotlin
// common/infrastructure/build.gradle.kts
plugins {
    `java-test-fixtures`  // testFixtures 소스셋 활성화
}

dependencies {
    testFixturesApi(libs.testcontainers.core)
    testFixturesApi(libs.testcontainers.kafka)
    testFixturesApi(libs.testcontainers.postgresql)
}

// queue-service/build.gradle.kts
dependencies {
    testImplementation(testFixtures(project(":common:infrastructure")))
}
```

**testFixtures에 위치한 것들:**
- `IntegrationTestBase`: Testcontainers 싱글턴 (Redis, Kafka, PostgreSQL)
- 공통 테스트 유틸리티

---

## application.yml (queue-service)

```yaml
server:
  port: 8081
  shutdown: graceful           # 진행 중인 요청 처리 후 종료

spring:
  application:
    name: queue-service
  lifecycle:
    timeout-per-shutdown-phase: 30s   # 최대 30초 대기 후 강제 종료
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2s              # Redis 연결 타임아웃
      lettuce:
        pool:
          max-active: 32       # 최대 동시 연결
          max-idle: 16         # 유휴 상태 최대 연결
          min-idle: 8          # 유휴 상태 최소 연결

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus  # 노출할 Actuator 엔드포인트
```

### Lettuce 커넥션 풀

| 설정 | 값 | 의미 |
|------|-----|------|
| `max-active` | 32 | 동시에 사용할 수 있는 최대 Redis 연결 수 |
| `max-idle` | 16 | 사용하지 않아도 유지하는 최대 연결 수 |
| `min-idle` | 8 | 항상 유지하는 최소 연결 수 (Cold Start 방지) |

---

## 해결한 환경 이슈

### 이슈 1: ktlint 플러그인 다운로드 실패 (403 Forbidden)

**증상:**
```
Could not resolve all artifacts for configuration ':classpath'.
> Could not resolve org.jlleitschuh.gradle.ktlint:org.jlleitschuh.gradle.ktlint.gradle.plugin:14.0.1
  > Could not get resource 'https://plugins-artifacts.gradle.org/.../ktlint-gradle-14.0.1.jar'
    > Server returned HTTP response code: 403
```

**원인:**
- Gradle 플러그인 포털(`plugins.gradle.org`)은 프록시에서 허용되지만, 실제 아티팩트 저장소(`plugins-artifacts.gradle.org`)로 리다이렉트됨
- `plugins-artifacts.gradle.org`가 프록시 화이트리스트에 없어서 403 반환

**시도한 해결책:**

| 방법 | 결과 | 이유 |
|------|------|------|
| `mavenCentral()`을 먼저 추가 | 실패 | 플러그인 포털이 먼저 시도되고 403이면 실패로 처리 (fallback 안 함) |
| `mavenCentral()` 단독 사용 | 실패 | 플러그인 마커 POM이 Maven Central에 없음 |
| `resolutionStrategy` | 실패 | 아티팩트 자체가 Maven Central에 없음 |

**최종 해결:** ktlint 플러그인 비활성화

```kotlin
// build.gradle.kts
// alias(libs.plugins.ktlint) apply false    ← 주석 처리
// apply(plugin = "org.jlleitschuh.gradle.ktlint")  ← 주석 처리
```

**향후 대응:** 프록시에 `plugins-artifacts.gradle.org` 도메인을 화이트리스트에 추가하면 주석 해제

---

### 이슈 2: Kotest 6.x + kotest-extensions-spring 버전 충돌

**증상:**
```
java.lang.NoSuchMethodError:
  'void io.kotest.core.spec.style.scopes.DescribeSpecContainerScope$addTest$1
  .<init>(SpecRef$Reference, ...)'
```

**원인 분석:**

```
의존성 트리:
kotest-runner-junit5:6.1.4
    └── kotest-framework-engine:6.1.4
        └── (Kotest 6.x의 SpecRef$Reference(KClass) 생성자)

kotest-extensions-spring:1.3.0
    └── kotest-framework-api:5.8.1  ← Kotest 5.x가 끌려 들어옴!
        └── (Kotest 5.x의 SpecRef$Reference(KClass, String) 생성자)

런타임에 5.x의 SpecRef$Reference가 로드되어 6.x 코드와 충돌
```

**핵심 포인트:**
- `kotest-extensions-spring:1.3.0`이 `kotest-framework-api:5.8.1`을 transitive 의존성으로 끌어옴
- Kotest 6.x에서는 `kotest-framework-api`라는 모듈명이 존재하지 않음 (6.x에서 재구조화)
- 5.x의 `SpecRef$Reference` 클래스가 classpath에 올라와서 6.x 코드와 시그니처 충돌

**시도한 해결책:**

| 방법 | 결과 | 이유 |
|------|------|------|
| `force()` kotest 모듈 | 실패 | `kotest-framework-api` 6.1.4 아티팩트가 존재하지 않음 |
| `eachDependency { useVersion }` | 실패 | 동일 이유 |

**최종 해결:** 충돌하는 모듈을 exclude

```kotlin
// queue-service/build.gradle.kts
configurations.testImplementation {
    exclude(group = "io.kotest", module = "kotest-framework-api")
}
configurations.testRuntimeOnly {
    exclude(group = "io.kotest", module = "kotest-framework-api")
}
```

**원리:** `kotest-framework-api:5.8.1`을 classpath에서 완전히 제거하면, Kotest 6.x가 자체적으로 제공하는 클래스들만 남아서 충돌 해소.

---

### 이슈 3: Spring Boot 4.0에서 @WebFluxTest 사용 불가

**증상:**
```
Unresolved reference: WebFluxTest
```

**원인:**
- Spring Boot 4.0에서 테스트 자동구성 모듈이 재구조화됨
- `@WebFluxTest`가 기존 `spring-boot-test-autoconfigure` JAR에서 분리되거나 이동

**해결:** `WebTestClient.bindToController()`로 Spring Context 없이 테스트

```kotlin
// ❌ Spring Boot 4.0에서 동작하지 않음
@WebFluxTest(QueueController::class)
class QueueControllerTest

// ✅ Spring Context 없이 직접 생성
class QueueControllerTest : DescribeSpec({
    val controller = QueueController(mockk(), mockk())
    val webTestClient = WebTestClient.bindToController(controller).build()
})
```

**장점:**
- Spring Context 로드 없이 빠르게 테스트
- 의존성을 직접 주입하므로 테스트 의도가 명확
- Spring Boot 버전 변경에 영향 안 받음

---

## 면접 질문

### Q1. Gradle의 Version Catalog(libs.versions.toml)을 왜 사용하나요?

**A:** 멀티모듈 프로젝트에서 의존성 버전을 한 곳에서 관리하기 위해서입니다.

기존 방식은 각 `build.gradle`에 버전 문자열이 흩어져 있어서, 라이브러리 업그레이드 시 여러 파일을 수정해야 하고 모듈 간 버전 불일치가 발생할 수 있었습니다. Version Catalog를 사용하면 `gradle/libs.versions.toml` 한 파일에서 모든 버전을 관리하고, `libs.kotest.runner.junit5` 같은 타입 안전한 접근자를 통해 IDE 자동완성과 컴파일 타임 검증을 받을 수 있습니다.

### Q2. `api`와 `implementation`의 차이는?

**A:** 둘 다 의존성을 추가하지만, transitive 노출 범위가 다릅니다.

- `api`: 이 모듈을 의존하는 다른 모듈에서도 해당 라이브러리를 직접 사용 가능. 예: common이 `api("webflux")`로 선언하면 queue-service에서 WebFlux 클래스를 직접 import 가능
- `implementation`: 이 모듈 내부에서만 사용. 의존하는 모듈에 노출되지 않음. 예: common이 `implementation("redisson")`으로 선언하면 queue-service에서 Redisson 클래스를 직접 사용 불가

`api`는 편리하지만 남용하면 의존성이 퍼져나가므로, 공개 인터페이스에 드러나는 것만 `api`로, 나머지는 `implementation`으로 선언합니다.

### Q3. Spring Boot AutoConfiguration은 어떻게 동작하나요?

**A:** Spring Boot는 시작 시 classpath에서 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 파일을 찾고, 거기 등록된 `@AutoConfiguration` 클래스를 자동으로 로드합니다.

이 프로젝트에서 `FlashSaleCommonAutoConfiguration`이 `@ComponentScan(basePackages = ["com.flashsale.common"])`을 포함하고 있어, queue-service의 `@SpringBootApplication`(`com.flashsale.queue`)이 스캔하지 못하는 `com.flashsale.common` 패키지의 빈들(TimeoutProperties, GlobalErrorHandler 등)을 자동으로 등록합니다.

핵심은 각 서비스가 common 모듈의 빈을 사용하기 위해 별도 설정을 하지 않아도 되며, common 모듈이 자체적으로 등록을 책임진다는 점입니다.

### Q4. testFixtures와 일반 test 소스셋의 차이는?

**A:** 일반 `test` 소스셋은 해당 모듈의 테스트에서만 사용할 수 있고, `testFixtures`는 다른 모듈의 테스트에서도 접근할 수 있습니다.

이 프로젝트에서 `IntegrationTestBase`(Testcontainers 설정)를 common 모듈의 `testFixtures`에 두고, queue-service가 `testImplementation(testFixtures(project(":common:infrastructure")))`로 참조합니다. 이를 통해 Redis/Kafka/PostgreSQL 컨테이너 설정을 한 곳에서 관리하면서 모든 서비스의 통합 테스트에서 공유합니다.

### Q5. transitive 의존성 충돌은 어떻게 해결하나요?

**A:** Gradle에서 transitive 의존성 충돌을 해결하는 방법은 세 가지입니다.

1. **`exclude`**: 특정 모듈을 classpath에서 완전히 제거. 이 프로젝트에서 `kotest-framework-api:5.8.1`을 exclude하여 Kotest 6.x 충돌을 해결
2. **`force()`**: 특정 버전으로 강제. 같은 모듈의 다른 버전이 있을 때 유효
3. **`eachDependency { useVersion() }`**: 더 세밀한 버전 재지정

이번 경우에는 `kotest-framework-api`가 Kotest 6.x에서 모듈명 자체가 사라졌기 때문에, `force()`나 `useVersion()`은 존재하지 않는 아티팩트를 요구하게 되어 실패했고, `exclude`만이 유효한 해결책이었습니다.

### Q6. Graceful Shutdown이란?

**A:** 서버 종료 시 진행 중인 요청을 완료한 후 종료하는 방식입니다.

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

동작 순서:
1. 종료 신호(SIGTERM) 수신
2. 새로운 요청 수신 거부
3. 진행 중인 요청이 완료될 때까지 대기 (최대 30초)
4. 30초 초과 시 강제 종료

선착순 시스템에서 중요한 이유: 사용자가 대기열 진입 API를 호출했는데 Redis에 ZADD는 성공했고 응답 반환 전에 서버가 죽으면, 사용자는 진입 실패로 알지만 실제로는 진입된 상태가 됩니다. Graceful Shutdown은 이런 상황을 방지합니다.

### Q7. Lettuce 커넥션 풀 설정의 의미는?

**A:** Lettuce는 Spring Data Redis의 기본 Redis 클라이언트이며, 커넥션 풀로 Redis 연결을 재사용합니다.

- `max-active: 32` — 동시에 Redis 명령을 실행할 수 있는 최대 연결 수. 초과 시 대기
- `max-idle: 16` — 사용하지 않는 연결 중 유지할 최대 수. 초과하는 유휴 연결은 닫힘
- `min-idle: 8` — 항상 유지할 최소 연결 수. Cold Start(첫 요청에 연결 생성 지연) 방지

선착순 시스템에서는 순간적으로 수만 건의 Redis 요청이 발생하므로, 적절한 풀 사이즈가 중요합니다. 너무 작으면 요청이 대기하고, 너무 크면 Redis 서버에 부하를 줍니다.
