package com.flashsale.queue.application.port.`in`

import com.flashsale.common.domain.Result
import com.flashsale.queue.domain.QueueError

interface GetQueuePositionUseCase {
    suspend fun execute(query: PositionQuery): Result<PositionResult, QueueError>
}

data class PositionQuery(val saleEventId: String, val userId: String)

data class PositionResult(val position: Long)
