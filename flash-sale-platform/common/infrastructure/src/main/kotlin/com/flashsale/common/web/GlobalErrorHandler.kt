package com.flashsale.common.web

import com.flashsale.common.logging.Log
import com.flashsale.common.logging.MdcKeys
import kotlinx.coroutines.TimeoutCancellationException
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.web.WebProperties
import org.springframework.boot.autoconfigure.web.reactive.error.AbstractErrorWebExceptionHandler
import org.springframework.boot.web.reactive.error.ErrorAttributes
import org.springframework.context.ApplicationContext
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * 글로벌 에러 핸들러.
 * 모든 미처리 예외를 일관된 ErrorResponse JSON으로 변환한다.
 *
 * 처리 순서:
 * 1. ResponseStatusException → HTTP 상태코드 유지
 * 2. TimeoutCancellationException → 504 Gateway Timeout
 * 3. IllegalArgumentException → 400 Bad Request
 * 4. 기타 → 500 Internal Server Error
 */
@Component
@Order(-2) // DefaultErrorWebExceptionHandler(-1)보다 높은 우선순위
class GlobalErrorHandler(
    errorAttributes: ErrorAttributes,
    applicationContext: ApplicationContext,
    serverCodecConfigurer: ServerCodecConfigurer,
) : AbstractErrorWebExceptionHandler(errorAttributes, WebProperties.Resources(), applicationContext) {
    companion object : Log

    init {
        super.setMessageReaders(serverCodecConfigurer.readers)
        super.setMessageWriters(serverCodecConfigurer.writers)
    }

    override fun getRoutingFunction(errorAttributes: ErrorAttributes): RouterFunction<ServerResponse> =
        RouterFunctions.route(RequestPredicates.all()) { request ->
            renderError(request)
        }

    private fun renderError(request: ServerRequest): Mono<ServerResponse> {
        val error = getError(request)
        val status = resolveStatus(error)
        val code = resolveCode(error)
        val message = resolveMessage(error, status)
        val requestId = MDC.get(MdcKeys.REQUEST_ID)

        if (status.is5xxServerError) {
            logger.error(error) { "[${request.method()}] ${request.path()} → $status" }
        } else {
            logger.warn { "[${request.method()}] ${request.path()} → $status: $message" }
        }

        val errorResponse = ErrorResponse(
            code = code,
            message = message,
            requestId = requestId,
            timestamp = Instant.now(),
        )

        return ServerResponse.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(errorResponse)
    }

    private fun resolveStatus(error: Throwable): HttpStatus =
        when (error) {
            is ResponseStatusException -> HttpStatus.valueOf(error.statusCode.value())
            is TimeoutCancellationException -> HttpStatus.GATEWAY_TIMEOUT
            is IllegalArgumentException -> HttpStatus.BAD_REQUEST
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }

    private fun resolveCode(error: Throwable): String =
        when (error) {
            is ResponseStatusException -> error.statusCode.value().toString()
            is TimeoutCancellationException -> "TIMEOUT"
            is IllegalArgumentException -> "BAD_REQUEST"
            else -> "INTERNAL_ERROR"
        }

    private fun resolveMessage(error: Throwable, status: HttpStatus): String =
        when {
            error is ResponseStatusException -> error.reason ?: status.reasonPhrase
            error is TimeoutCancellationException -> "요청 처리 시간 초과"
            status.is5xxServerError -> "서버 내부 오류가 발생했습니다"
            else -> error.message ?: status.reasonPhrase
        }
}
