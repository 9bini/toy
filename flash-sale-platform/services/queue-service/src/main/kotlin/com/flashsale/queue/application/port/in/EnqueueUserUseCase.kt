package com.flashsale.queue.application.port.`in`

import com.flashsale.common.domain.Result
import com.flashsale.queue.domain.QueueError

interface EnqueueUserUseCase {
    suspend fun execute(command: EnqueueCommand): Result<EnqueueResult, QueueError>
}

data class EnqueueCommand(val saleEventId: String, val userId: String)

data class EnqueueResult(val position: Long)
