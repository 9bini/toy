package com.flashsale.common.web

import java.time.Instant

/**
 * 표준 에러 응답 DTO.
 * 모든 서비스에서 일관된 에러 포맷을 보장한다.
 *
 * 응답 예시:
 * ```json
 * {
 *   "code": "TIMEOUT",
 *   "message": "요청 처리 시간 초과",
 *   "requestId": "01JMXYZ...",
 *   "timestamp": "2026-02-26T00:00:00Z"
 * }
 * ```
 */
data class ErrorResponse(
    val code: String,
    val message: String,
    val requestId: String? = null,
    val timestamp: Instant = Instant.now(),
)
