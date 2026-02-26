plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    group = "com.flashsale"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    dependencies {
        "implementation"(rootProject.libs.bundles.coroutines)
        "implementation"(rootProject.libs.kotlin.logging)

        "testImplementation"(rootProject.libs.bundles.kotest)
        "testImplementation"(rootProject.libs.kotest.extensions.spring)
        "testImplementation"(rootProject.libs.mockk)
        "testImplementation"(rootProject.libs.coroutines.test)
    }

    // Spring Boot 모듈: 컴파일 타임 컴포넌트 인덱싱으로 시작 시간 단축
    plugins.withId("org.springframework.boot") {
        dependencies {
            "annotationProcessor"("org.springframework:spring-context-indexer")
        }
    }

    // 서비스 모듈 공통 의존성 (actuator, reactor, prometheus, 테스트)
    if (project.path.startsWith(":services:")) {
        dependencies {
            "implementation"("org.springframework.boot:spring-boot-starter-actuator")
            "implementation"("io.projectreactor.kotlin:reactor-kotlin-extensions")
            "implementation"("io.micrometer:micrometer-registry-prometheus")

            "testImplementation"("org.springframework.boot:spring-boot-starter-test") {
                (this as ExternalModuleDependency).exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
            }
            "testImplementation"("io.projectreactor:reactor-test")
        }
    }
}
