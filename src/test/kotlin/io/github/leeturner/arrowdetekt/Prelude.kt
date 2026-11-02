package io.github.leeturner.arrowdetekt

internal val PRELUDE =
    """
    import arrow.core.Either
    import arrow.core.Either.Left
    import arrow.core.Either.Right
    import arrow.core.left
    import arrow.core.right
    import arrow.core.raise.Raise
    import arrow.core.raise.catch
    import arrow.core.raise.either
    import arrow.core.raise.ensure
    import arrow.core.raise.recover
    import arrow.core.raise.context.raise as raiseInContext
    import arrow.core.nonFatalOrThrow
    import java.io.IOException
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.flow.flow
    import kotlinx.coroutines.withContext
    import kotlin.coroutines.cancellation.CancellationException

    object E
    fun findUser(id: Int): Either<E, String> = Either.Right("u")
    fun plainCall(): Int = 1
    fun <T> runNow(block: () -> T): T = block()
    """.trimIndent() + "\n"
