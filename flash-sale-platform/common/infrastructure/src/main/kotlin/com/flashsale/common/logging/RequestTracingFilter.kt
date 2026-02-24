package com.flashsale.common.logging

import com.flashsale.common.domain.IdGenerator
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange

/**
 * 모든 HTTP 요청에 requestId를 부여하는 WebFilter.
 *
 * 동작:
 * 1. 요청 헤더에 X-Request-Id가 있으면 그대로 사용 (서비스 간 전파)
 * 2. 없으면 새로 생성
 * 3. MDC에 세팅하여 이후 모든 로그에 requestId가 포함됨
 * 4. 응답 헤더에도 X-Request-Id를 추가하여 클라이언트가 추적 가능
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestTracingFilter : CoWebFilter() {
    companion object : Log {
        const val HEADER_REQUEST_ID = "X-Request-Id"
    }

    override suspend fun filter(
        exchange: ServerWebExchange,
        chain: CoWebFilterChain,
    ) {
        val requestId = exchange.request.extractRequestId()
        exchange.response.headers.add(HEADER_REQUEST_ID, requestId)

        // withMdc가 MDC put/remove를 모두 관리 — 이중 세팅 없음
        withMdc(MdcKeys.REQUEST_ID, requestId) {
            logger.debug {
                "${exchange.request.method} ${exchange.request.path}"
            }
            chain.filter(exchange)
        }
    }

    private fun ServerHttpRequest.extractRequestId(): String = headers.getFirst(HEADER_REQUEST_ID) ?: IdGenerator.generate()
}
