package com.flashsale.common.domain

/**
 * 비즈니스 로직의 성공/실패를 표현하는 Result 타입.
 * Exception 대신 타입 시스템으로 에러를 처리한다.
 *
 * 사용 예시:
 * ```kotlin
 * suspend fun placeOrder(request: OrderRequest): Result<Order, OrderError> {
 *     val stock = stockPort.getStock(request.productId)
 *         ?: return Result.failure(OrderError.ProductNotFound(request.productId))
 *
 *     if (stock < request.quantity) {
 *         return Result.failure(OrderError.InsufficientStock(stock, request.quantity))
 *     }
 *
 *     return Result.success(Order.create(request))
 * }
 * ```
 */
sealed interface Result<out T, out E> {
    data class Success<T>(
        val value: T,
    ) : Result<T, Nothing>

    data class Failure<E>(
        val error: E,
    ) : Result<Nothing, E>

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    companion object {
        fun <T> success(value: T): Result<T, Nothing> = Success(value)

        fun <E> failure(error: E): Result<Nothing, E> = Failure(error)
    }
}

/** 성공 값을 변환한다 */
inline fun <T, E, R> Result<T, E>.map(transform: (T) -> R): Result<R, E> =
    when (this) {
        is Result.Success -> Result.success(transform(value))
        is Result.Failure -> this
    }

/** 성공 값을 다른 Result로 체이닝한다 */
inline fun <T, E, R> Result<T, E>.flatMap(transform: (T) -> Result<R, E>): Result<R, E> =
    when (this) {
        is Result.Success -> transform(value)
        is Result.Failure -> this
    }

/** 에러를 다른 타입으로 변환한다 */
inline fun <T, E, F> Result<T, E>.mapError(transform: (E) -> F): Result<T, F> =
    when (this) {
        is Result.Success -> this
        is Result.Failure -> Result.failure(transform(error))
    }

/** 성공 값을 꺼낸다. 실패 시 default 반환 */
inline fun <T, E> Result<T, E>.getOrElse(default: (E) -> T): T =
    when (this) {
        is Result.Success -> value
        is Result.Failure -> default(error)
    }

/** 성공 값을 꺼낸다. 실패 시 null 반환 */
fun <T, E> Result<T, E>.getOrNull(): T? =
    when (this) {
        is Result.Success -> value
        is Result.Failure -> null
    }

/** 성공/실패에 따라 분기 처리한다 */
inline fun <T, E, R> Result<T, E>.fold(
    onSuccess: (T) -> R,
    onFailure: (E) -> R,
): R =
    when (this) {
        is Result.Success -> onSuccess(value)
        is Result.Failure -> onFailure(error)
    }

/** 성공 시 사이드 이펙트를 실행한다 */
inline fun <T, E> Result<T, E>.onSuccess(action: (T) -> Unit): Result<T, E> {
    if (this is Result.Success) action(value)
    return this
}

/** 실패 시 사이드 이펙트를 실행한다 */
inline fun <T, E> Result<T, E>.onFailure(action: (E) -> Unit): Result<T, E> {
    if (this is Result.Failure) action(error)
    return this
}
