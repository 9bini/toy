package com.flashsale.queue.domain

import java.time.Instant

/** 대기열 항목. Redis Sorted Set의 member/score 매핑 단위. */
data class QueueEntry(
    val saleEventId: String,
    val userId: String,
    /** Sorted Set score로 사용 (millis). 먼저 진입한 사용자가 낮은 score */
    val enteredAt: Instant,
)
