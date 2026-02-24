// 알림 서비스: SSE + Push + 외부 API 병렬 발송 + Kafka Consumer
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

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit.jupiter)
}
