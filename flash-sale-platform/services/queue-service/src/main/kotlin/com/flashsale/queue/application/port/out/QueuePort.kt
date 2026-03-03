package com.flashsale.queue.application.port.out

import com.flashsale.queue.domain.QueueEntry

/** 대기열 저장소 포트. 기술 세부사항을 노출하지 않는다. */
interface QueuePort {
    /** 대기열에 사용자 추가. 이미 존재하면 false 반환 */
    suspend fun add(entry: QueueEntry): Boolean

    /** 사용자의 현재 순번 조회 (1-based). 없으면 null */
    suspend fun getPosition(saleEventId: String, userId: String): Long?
}
