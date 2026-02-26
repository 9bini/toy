// 공유 인프라 모듈: Redis, Kafka 공통 설정, 로깅, 유틸리티, Resilience4j
plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

tasks.getByName<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}

tasks.getByName<Jar>("jar") {
    enabled = true
}

dependencies {
    api(project(":common:domain"))

    // 서비스에서 transitive하게 접근해야 하는 핵심 의존성
    api("org.springframework.boot:spring-boot-starter-webflux")
    api("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    api("org.springframework.kafka:spring-kafka")
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    api(libs.jackson.module.blackbird)

    // Resilience4j: 서킷 브레이커, Rate Limiter, Retry
    api(libs.bundles.resilience4j)
    api("org.springframework.boot:spring-boot-starter-aop")

    // 내부 구현 전용 (서비스에서 직접 접근 불필요)
    implementation(libs.redisson.spring.boot.starter)
    implementation(libs.logstash.logback.encoder)

    // testFixtures: 통합 테스트용 Testcontainers 공통 클래스
    testFixturesApi(libs.testcontainers.core)
    testFixturesApi(libs.testcontainers.kafka)
    testFixturesApi(libs.testcontainers.junit.jupiter)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.testcontainers.r2dbc)
    testFixturesApi(libs.redis.testcontainers)
    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
}
