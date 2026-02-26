// 결제 서비스: Saga 패턴 + 멱등성 키 + 보상 트랜잭션
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":common:infrastructure"))

    // Redisson: 멱등성 키 분산 락
    implementation(libs.redisson.spring.boot.starter)

    // R2DBC: PostgreSQL 영속
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    runtimeOnly(libs.r2dbc.postgresql)

    // Flyway: DB 마이그레이션
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql.jdbc)

    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.r2dbc)
    testImplementation(libs.redis.testcontainers)
}
