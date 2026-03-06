plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    // ktlint 플러그인: plugins-artifacts.gradle.org 접근 불가 시 비활성화
    // alias(libs.plugins.ktlint) apply false
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
    // apply(plugin = "org.jlleitschuh.gradle.ktlint")

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
        jvmArgs("-Dfile.encoding=UTF-8")
        // Kotest uses Korean test names; disable HTML reports to avoid
        // filesystem encoding issues on POSIX-locale systems.
        reports.html.required.set(false)
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    dependencies {
        "implementation"(rootProject.libs.bundles.coroutines)
        "implementation"(rootProject.libs.kotlin.logging)

        "testImplementation"(rootProject.libs.bundles.kotest)
        "testImplementation"(rootProject.libs.kotest.extensions.spring)
        "testImplementation"(rootProject.libs.mockk)
        "testImplementation"(rootProject.libs.coroutines.test)
    }

    // 서비스 모듈 공통 의존성 (actuator, reactor, prometheus, 테스트)
    if (project.path.startsWith(":services:")) {
        dependencies {
            "implementation"("org.springframework.boot:spring-boot-starter-actuator")
            "implementation"("io.projectreactor.kotlin:reactor-kotlin-extensions")
            "implementation"("io.micrometer:micrometer-registry-prometheus")

            "testImplementation"("org.springframework.boot:spring-boot-starter-test")
            "testImplementation"("io.projectreactor:reactor-test")
        }

        // integrationTest source set for Testcontainers-based tests (requires Docker)
        val sourceSets = the<SourceSetContainer>()
        val integrationTest by sourceSets.creating {
            compileClasspath += sourceSets["main"].output + sourceSets["test"].output
            runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
        }

        configurations[integrationTest.implementationConfigurationName].extendsFrom(
            configurations["testImplementation"],
        )
        configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(
            configurations["testRuntimeOnly"],
        )

        tasks.register<Test>("integrationTest") {
            description = "Run integration tests (requires Docker)"
            group = "verification"
            testClassesDirs = integrationTest.output.classesDirs
            classpath = integrationTest.runtimeClasspath
            useJUnitPlatform()
            shouldRunAfter(tasks.named("test"))
        }
    }
}
