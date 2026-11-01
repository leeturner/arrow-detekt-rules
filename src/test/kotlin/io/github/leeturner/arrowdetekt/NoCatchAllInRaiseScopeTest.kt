package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

class NoCatchAllInRaiseScopeTest {
    private val env = createEnvironment()
    private val rule = NoCatchAllInRaiseScope(Config.empty)

    private fun lint(body: String): List<Finding> = rule.lintWithContext(env, PRELUDE + body)

    @Test
    fun flagsRunCatchingAroundBind() {
        val findings = lint("fun f() = either<E, String?> { runCatching { findUser(1).bind() }.getOrNull() }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsReceiverRunCatching() {
        val findings = lint("fun f() = either<E, String?> { 1.runCatching { findUser(this).bind() }.getOrNull() }")

        assertEquals(1, findings.size)
    }

    @Test
    fun ignoresEitherCatch() {
        val findings = lint("""fun f() = either<E, String> { Either.catch { plainCall() }.mapLeft { E }.bind(); "x" }""")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresRunCatchingWithoutRaise() {
        val findings = lint("fun f() = either<E, Int> { runCatching { plainCall() }.getOrDefault(0) }")

        assertEquals(0, findings.size)
    }

    private companion object {
        val PRELUDE =
            """
            import arrow.core.Either
            import arrow.core.raise.Raise
            import arrow.core.raise.either
            import arrow.core.raise.ensure
            import arrow.core.raise.recover
            import arrow.core.nonFatalOrThrow
            import java.io.IOException
            import kotlin.coroutines.cancellation.CancellationException

            object E
            fun findUser(id: Int): Either<E, String> = Either.Right("u")
            fun plainCall(): Int = 1
            """.trimIndent() + "\n"
    }
}
