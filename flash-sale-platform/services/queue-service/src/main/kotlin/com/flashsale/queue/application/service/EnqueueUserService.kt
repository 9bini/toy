package com.flashsale.queue.application.service

import com.flashsale.common.domain.Result
import com.flashsale.common.logging.Log
import com.flashsale.queue.application.port.`in`.EnqueueCommand
import com.flashsale.queue.application.port.`in`.EnqueueResult
import com.flashsale.queue.application.port.`in`.EnqueueUserUseCase
import com.flashsale.queue.application.port.out.QueuePort
import com.flashsale.queue.domain.QueueEntry
import com.flashsale.queue.domain.QueueError
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class EnqueueUserService(
    private val queuePort: QueuePort,
) : EnqueueUserUseCase {
    companion object : Log

    override suspend fun execute(command: EnqueueCommand): Result<EnqueueResult, QueueError> {
        val entry = QueueEntry(
            saleEventId = command.saleEventId,
            userId = command.userId,
            enteredAt = Instant.now(),
        )

        val added = queuePort.add(entry)
        if (!added) {
            logger.info { "대기열 중복 진입 시도: userId=${command.userId}, saleEventId=${command.saleEventId}" }
            return Result.failure(QueueError.AlreadyEnqueued(command.userId, command.saleEventId))
        }

        val position = queuePort.getPosition(command.saleEventId, command.userId)
            ?: return Result.failure(QueueError.NotFound(command.userId, command.saleEventId))

        logger.info { "대기열 진입 완료: userId=${command.userId}, position=$position" }
        return Result.success(EnqueueResult(position))
    }
}
