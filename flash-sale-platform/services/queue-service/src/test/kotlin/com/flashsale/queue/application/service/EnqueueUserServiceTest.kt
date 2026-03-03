package com.flashsale.queue.application.service

import com.flashsale.common.domain.Result
import com.flashsale.queue.application.port.`in`.EnqueueCommand
import com.flashsale.queue.application.port.out.QueuePort
import com.flashsale.queue.domain.QueueError
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk

class EnqueueUserServiceTest : DescribeSpec({
    val queuePort = mockk<QueuePort>()
    val sut = EnqueueUserService(queuePort)

    describe("execute") {
        val command = EnqueueCommand(saleEventId = "sale-001", userId = "user-001")

        context("새로운 사용자가 대기열에 진입하면") {
            it("position을 반환한다") {
                coEvery { queuePort.add(any()) } returns true
                coEvery { queuePort.getPosition("sale-001", "user-001") } returns 1L

                val result = sut.execute(command)

                result.shouldBeInstanceOf<Result.Success<*>>()
                (result as Result.Success).value.position shouldBe 1L
            }
        }

        context("이미 대기열에 있는 사용자가 재진입하면") {
            it("AlreadyEnqueued 에러를 반환한다") {
                coEvery { queuePort.add(any()) } returns false

                val result = sut.execute(command)

                result.shouldBeInstanceOf<Result.Failure<*>>()
                (result as Result.Failure).error.shouldBeInstanceOf<QueueError.AlreadyEnqueued>()
            }
        }
    }
})
