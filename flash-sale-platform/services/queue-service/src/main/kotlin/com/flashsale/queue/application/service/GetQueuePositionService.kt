package com.flashsale.queue.application.service

import com.flashsale.common.domain.Result
import com.flashsale.queue.application.port.`in`.GetQueuePositionUseCase
import com.flashsale.queue.application.port.`in`.PositionQuery
import com.flashsale.queue.application.port.`in`.PositionResult
import com.flashsale.queue.application.port.out.QueuePort
import com.flashsale.queue.domain.QueueError
import org.springframework.stereotype.Service

@Service
class GetQueuePositionService(
    private val queuePort: QueuePort,
) : GetQueuePositionUseCase {
    override suspend fun execute(query: PositionQuery): Result<PositionResult, QueueError> {
        val position = queuePort.getPosition(query.saleEventId, query.userId)
            ?: return Result.failure(QueueError.NotFound(query.userId, query.saleEventId))

        return Result.success(PositionResult(position))
    }
}
