package com.flashsale.common.logging

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 코틀린 로거를 한 줄로 생성하는 유틸.
 *
 * 사용법 1: 클래스 내부 companion object (로거가 클래스당 1개로 캐싱됨)
 * ```kotlin
 * class OrderService {
 *     companion object : Log
 *
 *     suspend fun placeOrder(request: OrderRequest) {
 *         logger.info { "주문 생성 시작: productId=${request.productId}" }
 *     }
 * }
 * ```
 *
 * 사용법 2: 패키지 레벨 (유틸 함수 등)
 * ```kotlin
 * private val logger = logger("RetryUtil")
 * ```
 */
interface Log {
    // lazy 프로퍼티로 로거 인스턴스를 클래스당 1번만 생성
    val logger
        get() =
            loggerCache.getOrPut(this::class.java) {
                KotlinLogging.logger(this::class.java.enclosingClass?.name ?: this::class.java.name)
            }

    companion object {
        // companion object는 JVM에서 클래스당 싱글톤이므로 캐시 크기가 제한적
        private val loggerCache = java.util.concurrent.ConcurrentHashMap<Class<*>, io.github.oshai.kotlinlogging.KLogger>()
    }
}

/** 이름을 직접 지정하여 로거 생성 */
fun logger(name: String) = KotlinLogging.logger(name)

/** 클래스를 기반으로 로거 생성 */
inline fun <reified T> logger() = KotlinLogging.logger(T::class.java.name)
