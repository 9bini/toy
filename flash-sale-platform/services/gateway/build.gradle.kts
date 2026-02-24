// Gateway 서비스: API Gateway + Rate Limiting (Redis Token Bucket)
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
}
