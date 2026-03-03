package com.flashsale.queue.adapter.`in`.web

import com.flashsale.common.domain.fold
import com.flashsale.common.web.ErrorResponse
import com.flashsale.queue.application.port.`in`.EnqueueCommand
import com.flashsale.queue.application.port.`in`.EnqueueUserUseCase
import com.flashsale.queue.application.port.`in`.GetQueuePositionUseCase
import com.flashsale.queue.application.port.`in`.PositionQuery
import com.flashsale.queue.domain.QueueError
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/queues")
class QueueController(
    private val enqueueUserUseCase: EnqueueUserUseCase,
    private val getQueuePositionUseCase: GetQueuePositionUseCase,
) {
    @PostMapping("/{saleEventId}/enter")
    suspend fun enter(
        @PathVariable saleEventId: String,
        @RequestBody request: EnqueueRequest,
    ): ResponseEntity<Any> {
        val command = EnqueueCommand(saleEventId, request.userId)
        return enqueueUserUseCase.execute(command).fold(
            onSuccess = { result ->
                ResponseEntity.status(HttpStatus.CREATED).body(
                    QueuePositionResponse(saleEventId, request.userId, result.position),
                )
            },
            onFailure = { error -> toErrorResponse(error) },
        )
    }

    @GetMapping("/{saleEventId}/position")
    suspend fun getPosition(
        @PathVariable saleEventId: String,
        @RequestParam userId: String,
    ): ResponseEntity<Any> {
        val query = PositionQuery(saleEventId, userId)
        return getQueuePositionUseCase.execute(query).fold(
            onSuccess = { result ->
                ResponseEntity.ok(
                    QueuePositionResponse(saleEventId, userId, result.position),
                )
            },
            onFailure = { error -> toErrorResponse(error) },
        )
    }

    private fun toErrorResponse(error: QueueError): ResponseEntity<Any> =
        when (error) {
            is QueueError.AlreadyEnqueued -> ResponseEntity.status(HttpStatus.CONFLICT).body(
                ErrorResponse(code = "ALREADY_ENQUEUED", message = "이미 대기열에 진입한 사용자입니다"),
            )
            is QueueError.NotFound -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ErrorResponse(code = "NOT_FOUND", message = "대기열에서 사용자를 찾을 수 없습니다"),
            )
        }
}
