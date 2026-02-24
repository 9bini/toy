// 통합 테스트 모듈: 서비스 간 통합 테스트
plugins {
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
    implementation(project(":common:infrastructure"))
    implementation(project(":services:gateway"))
    implementation(project(":services:queue-service"))
    implementation(project(":services:order-service"))
    implementation(project(":services:payment-service"))
    implementation(project(":services:notification-service"))

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("io.projectreactor:reactor-test")
    testImplementation(libs.bundles.testcontainers.full)
}
