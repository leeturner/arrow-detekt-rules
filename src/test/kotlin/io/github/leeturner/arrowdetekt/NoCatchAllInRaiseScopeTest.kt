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

    @Test
    fun flagsCatchException() {
        val findings = lint("fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsCatchThrowable() {
        val findings = lint("fun f() = either<E, Unit> { try { raise(E) } catch (e: Throwable) { } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsCatchCancellationException() {
        val findings = lint("fun f() = either<E, Unit> { try { raise(E) } catch (e: CancellationException) { } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsInRaiseExtensionFunction() {
        val findings = lint("fun Raise<E>.load(x: Boolean) { try { ensure(x) { E } } catch (e: Exception) { } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsBindInsideInlineLambda() {
        val findings = lint(
            "fun f() = either<E, Unit> { try { listOf(1, 2).forEach { findUser(it).bind() } } catch (e: Exception) { } }",
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsCatchThatLogsButDoesNotRethrow() {
        val findings = lint("fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { println(e) } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsOnlyTheBroadClause() {
        val code = "fun f() = either<E, Unit> {\ntry { raise(E) }\ncatch (e: IOException) { }\ncatch (e: Exception) { }\n}"

        val findings = lint(code)

        assertEquals(1, findings.size)
        assertEquals(PRELUDE.lines().size + 3, findings.single().entity.location.source.line)
    }

    @Test
    fun ignoresNarrowCatch() {
        val findings = lint("fun f() = either<E, Unit> { try { raise(E) } catch (e: IOException) { } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresRethrow() {
        val findings = lint("fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { throw e } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresNonFatalOrThrow() {
        val findings = lint("fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { e.nonFatalOrThrow() } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresEitherOpenedInsideTry() {
        val findings = lint("fun f() = try { either<E, Unit> { raise(E) } } catch (e: Exception) { null }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresRecoverOpenedInsideRunCatching() {
        val findings = lint("fun f() = runCatching { recover<E, Int>({ raise(E) }) { 0 } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsOuterScopeRaiseNextToInnerScope() {
        val findings = lint(
            "fun f() = either<E, Unit> { try { either<E, Unit> { raise(E) }; raise(E) } catch (e: Exception) { } }",
        )

        assertEquals(1, findings.size)
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
