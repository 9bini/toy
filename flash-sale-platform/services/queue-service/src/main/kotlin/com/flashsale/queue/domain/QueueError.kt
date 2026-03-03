package com.flashsale.queue.domain

sealed interface QueueError {
    /** 이미 대기열에 진입한 사용자가 재진입 시도 */
    data class AlreadyEnqueued(val userId: String, val saleEventId: String) : QueueError

    /** 대기열에서 사용자를 찾을 수 없음 */
    data class NotFound(val userId: String, val saleEventId: String) : QueueError
}
