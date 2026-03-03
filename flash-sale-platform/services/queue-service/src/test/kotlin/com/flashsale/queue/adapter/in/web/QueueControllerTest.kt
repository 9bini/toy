package com.flashsale.queue.adapter.`in`.web

import com.flashsale.common.domain.Result
import com.flashsale.queue.application.port.`in`.EnqueueResult
import com.flashsale.queue.application.port.`in`.EnqueueUserUseCase
import com.flashsale.queue.application.port.`in`.GetQueuePositionUseCase
import com.flashsale.queue.application.port.`in`.PositionResult
import com.flashsale.queue.domain.QueueError
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.coEvery
import io.mockk.mockk
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class QueueControllerTest : DescribeSpec({
    val enqueueUserUseCase = mockk<EnqueueUserUseCase>()
    val getQueuePositionUseCase = mockk<GetQueuePositionUseCase>()
    val controller = QueueController(enqueueUserUseCase, getQueuePositionUseCase)
    val webTestClient = WebTestClient.bindToController(controller).build()

    describe("POST /api/queues/{saleEventId}/enter") {
        context("정상 진입 시") {
            it("201 Created와 position을 반환한다") {
                coEvery { enqueueUserUseCase.execute(any()) } returns
                    Result.success(EnqueueResult(position = 1))

                webTestClient.post()
                    .uri("/api/queues/sale-001/enter")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"userId": "user-001"}""")
                    .exchange()
                    .expectStatus().isCreated
                    .expectBody()
                    .jsonPath("$.position").isEqualTo(1)
                    .jsonPath("$.saleEventId").isEqualTo("sale-001")
                    .jsonPath("$.userId").isEqualTo("user-001")
            }
        }

        context("이미 대기열에 진입한 사용자면") {
            it("409 Conflict를 반환한다") {
                coEvery { enqueueUserUseCase.execute(any()) } returns
                    Result.failure(QueueError.AlreadyEnqueued("user-001", "sale-001"))

                webTestClient.post()
                    .uri("/api/queues/sale-001/enter")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"userId": "user-001"}""")
                    .exchange()
                    .expectStatus().isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("ALREADY_ENQUEUED")
            }
        }
    }

    describe("GET /api/queues/{saleEventId}/position") {
        context("대기열에 있는 사용자를 조회하면") {
            it("200 OK와 position을 반환한다") {
                coEvery { getQueuePositionUseCase.execute(any()) } returns
                    Result.success(PositionResult(position = 42))

                webTestClient.get()
                    .uri("/api/queues/sale-001/position?userId=user-001")
                    .exchange()
                    .expectStatus().isOk
                    .expectBody()
                    .jsonPath("$.position").isEqualTo(42)
            }
        }

        context("대기열에 없는 사용자를 조회하면") {
            it("404 Not Found를 반환한다") {
                coEvery { getQueuePositionUseCase.execute(any()) } returns
                    Result.failure(QueueError.NotFound("user-001", "sale-001"))

                webTestClient.get()
                    .uri("/api/queues/sale-001/position?userId=user-001")
                    .exchange()
                    .expectStatus().isNotFound
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("NOT_FOUND")
            }
        }
    }
})
