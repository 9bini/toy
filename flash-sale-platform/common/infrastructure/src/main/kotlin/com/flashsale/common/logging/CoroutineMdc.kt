package com.flashsale.common.logging

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC

// 코루틴 환경에서 MDC를 안전하게 전파하는 유틸.
// WebFlux + 코루틴 환경에서는 스레드가 수시로 바뀌므로 MDC가 자동으로 전파되지 않는다.
//
// 사용법:
//   단일 키: withMdc(key, value) { }
//   2개 키: withMdc(key1, value1, key2, value2) { }
//   3개 이상: withMdc("k1" to "v1", "k2" to "v2", "k3" to "v3") { }

/** 단일 MDC 키-값 추가 후 코루틴 실행 (Pair 할당 없이 직접 처리) */
suspend inline fun <T> withMdc(
    key: String,
    value: String,
    crossinline block: suspend () -> T,
): T {
    val previous = MDC.get(key)
    MDC.put(key, value)
    return try {
        withContext(MDCContext()) {
            block()
        }
    } finally {
        if (previous == null) MDC.remove(key) else MDC.put(key, previous)
    }
}

/** 2개 MDC 키-값 추가 후 코루틴 실행 */
suspend inline fun <T> withMdc(
    key1: String,
    value1: String,
    key2: String,
    value2: String,
    crossinline block: suspend () -> T,
): T {
    val prev1 = MDC.get(key1)
    val prev2 = MDC.get(key2)
    MDC.put(key1, value1)
    MDC.put(key2, value2)
    return try {
        withContext(MDCContext()) {
            block()
        }
    } finally {
        if (prev1 == null) MDC.remove(key1) else MDC.put(key1, prev1)
        if (prev2 == null) MDC.remove(key2) else MDC.put(key2, prev2)
    }
}

/** 여러 MDC 키-값 추가 (3개 이상일 때만 vararg 사용) */
suspend inline fun <T> withMdc(
    vararg pairs: Pair<String, String>,
    crossinline block: suspend () -> T,
): T {
    val previous = Array(pairs.size) { i -> pairs[i].first to MDC.get(pairs[i].first) }
    pairs.forEach { (key, value) -> MDC.put(key, value) }
    return try {
        withContext(MDCContext()) {
            block()
        }
    } finally {
        previous.forEach { (key, oldValue) ->
            if (oldValue == null) MDC.remove(key) else MDC.put(key, oldValue)
        }
    }
}

/**
 * 표준 MDC 키 상수.
 * 로그에서 일관된 필드명을 보장한다.
 */
object MdcKeys {
    /** 요청 추적 ID (전 서비스 공유) */
    const val REQUEST_ID = "requestId"

    /** 현재 서비스명 */
    const val SERVICE = "service"

    /** 사용자 ID */
    const val USER_ID = "userId"

    /** 주문 ID */
    const val ORDER_ID = "orderId"

    /** 상품 ID */
    const val PRODUCT_ID = "productId"
}
