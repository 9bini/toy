pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "flash-sale-platform"

// 공통 모듈
include("common:domain")
include("common:infrastructure")

// 마이크로서비스
include("services:gateway")
include("services:queue-service")
include("services:order-service")
include("services:payment-service")
include("services:notification-service")

// 통합 테스트
include("tests:integration")
