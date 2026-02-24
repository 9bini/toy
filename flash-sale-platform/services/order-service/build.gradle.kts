// 주문 서비스: Redis Lua Script 재고 차감 + Redisson 분산 락 + Kafka Producer
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":common:infrastructure"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Redisson: 분산 락 직접 사용
    implementation(libs.redisson.spring.boot.starter)

    // R2DBC: PostgreSQL 영속
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    runtimeOnly(libs.r2dbc.postgresql)

    // Flyway: DB 마이그레이션 (R2DBC 환경에서 JDBC로 실행)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql.jdbc)

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.r2dbc)
    testImplementation(libs.redis.testcontainers)
}
