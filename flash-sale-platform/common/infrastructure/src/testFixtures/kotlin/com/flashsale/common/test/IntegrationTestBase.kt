package com.flashsale.common.test

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 통합 테스트 공통 Base 클래스.
 * Redis, Kafka, PostgreSQL Testcontainers를 싱글턴으로 관리한다.
 *
 * 사용 예시:
 * ```kotlin
 * @SpringBootTest
 * class OrderIntegrationTest : IntegrationTestBase() {
 *     // 테스트 작성...
 * }
 * ```
 *
 * 각 컨테이너는 JVM 프로세스 내에서 1번만 생성되며,
 * 모든 테스트 클래스가 공유한다 (테스트 속도 최적화).
 */
abstract class IntegrationTestBase {

    companion object {
        // --- Redis ---
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .apply { start() }

        // --- Kafka (KRaft 모드) ---
        @JvmStatic
        val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"))
            .apply { start() }

        // --- PostgreSQL ---
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("flashsale")
            .withUsername("flashsale")
            .withPassword("flashsale123")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            // Redis
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }

            // Kafka
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }

            // PostgreSQL (R2DBC)
            registry.add("spring.r2dbc.url") {
                "r2dbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/${postgres.databaseName}"
            }
            registry.add("spring.r2dbc.username") { postgres.username }
            registry.add("spring.r2dbc.password") { postgres.password }

            // PostgreSQL (Flyway - JDBC)
            registry.add("spring.flyway.url") { postgres.jdbcUrl }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
        }
    }
}
