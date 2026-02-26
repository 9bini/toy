package com.flashsale.common.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Jackson ObjectMapper 공통 설정.
 * 각 서비스 application.yml의 jackson 설정을 대체한다.
 *
 * Blackbird 모듈은 classpath에 있으면 Spring Boot가 자동 등록하므로
 * 별도 설정 없이 bytecode 기반 직렬화 최적화가 적용된다.
 */
@Configuration
class JacksonConfig {
    @Bean
    fun jacksonCustomizer() =
        Jackson2ObjectMapperBuilderCustomizer { builder ->
            builder
                .featuresToDisable(
                    SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                )
        }
}
