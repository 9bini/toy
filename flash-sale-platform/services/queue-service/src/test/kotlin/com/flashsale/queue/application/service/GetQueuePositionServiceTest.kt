package com.flashsale.queue.application.service

import com.flashsale.common.domain.Result
import com.flashsale.queue.application.port.`in`.PositionQuery
import com.flashsale.queue.application.port.out.QueuePort
import com.flashsale.queue.domain.QueueError
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk

class GetQueuePositionServiceTest : DescribeSpec({
    val queuePort = mockk<QueuePort>()
    val sut = GetQueuePositionService(queuePort)

    describe("execute") {
        val query = PositionQuery(saleEventId = "sale-001", userId = "user-001")

        context("대기열에 존재하는 사용자를 조회하면") {
            it("현재 순번을 반환한다") {
                coEvery { queuePort.getPosition("sale-001", "user-001") } returns 5L

                val result = sut.execute(query)

                result.shouldBeInstanceOf<Result.Success<*>>()
                (result as Result.Success).value.position shouldBe 5L
            }
        }

        context("대기열에 없는 사용자를 조회하면") {
            it("NotFound 에러를 반환한다") {
                coEvery { queuePort.getPosition("sale-001", "user-001") } returns null

                val result = sut.execute(query)

                result.shouldBeInstanceOf<Result.Failure<*>>()
                (result as Result.Failure).error.shouldBeInstanceOf<QueueError.NotFound>()
            }
        }
    }
})
