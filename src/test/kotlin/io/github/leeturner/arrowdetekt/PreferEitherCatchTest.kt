package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

class PreferEitherCatchTest {
    private val env = createEnvironment()
    private val rule = PreferEitherCatch(Config.empty)

    private fun lint(body: String): List<Finding> =
        rule.lintWithContext(env, PRELUDE + "class Failed(val cause: Throwable)\n" + body)

    @Test
    fun flagsCatchThatOnlyRaises() {
        val findings = lint("fun f() = either<E, Int> { catch({ plainCall() }) { raise(E) } }")

        assertEquals("Use Either.catch { ... }.mapLeft { E }.bind() instead of catch({ ... }) { raise(E) }.", findings.single().message)
    }

    @Test
    fun keepsItInSuggestion() {
        val findings = lint("fun f() = either<Failed, Int> { catch({ plainCall() }) { raise(Failed(it)) } }")

        assertEquals(
            "Use Either.catch { ... }.mapLeft { Failed(it) }.bind() instead of catch({ ... }) { raise(Failed(it)) }.",
            findings.single().message,
        )
    }

    @Test
    fun keepsNamedParameterInSuggestion() {
        val findings = lint("fun f() = either<Failed, Int> { catch({ plainCall() }) { e -> raise(Failed(e)) } }")

        assertEquals(
            "Use Either.catch { ... }.mapLeft { e -> Failed(e) }.bind() instead of catch({ ... }) { e -> raise(Failed(e)) }.",
            findings.single().message,
        )
    }

    @Test
    fun keepsMessageOnOneLineForMultiLineHandler() {
        val findings = lint("fun f() = either<E, Int> {\n    catch({ plainCall() }) {\n        raise(E)\n    }\n}")

        assertEquals("Use Either.catch { ... }.mapLeft { E }.bind() instead of catch({ ... }) { raise(E) }.", findings.single().message)
    }

    @Test
    fun flagsBothArgumentsInParentheses() {
        val findings = lint("fun f() = either<E, Int> { catch({ plainCall() }, { raise(E) }) }")

        assertEquals(1, findings.size)
    }

    @Test
    fun keepsFunctionReferenceBlockInSuggestion() {
        val findings = lint("fun f() = either<E, Int> { catch(::plainCall) { raise(E) } }")

        assertEquals("Use Either.catch(::plainCall).mapLeft { E }.bind() instead of catch(::plainCall) { raise(E) }.", findings.single().message)
    }

    @Test
    fun flagsInRaiseExtensionFunction() {
        val findings = lint("fun Raise<E>.g(): Int = catch({ plainCall() }) { raise(E) }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsRaiseThroughContextParameter() {
        val findings = lint("context(r: Raise<E>) fun g(): Int = catch({ plainCall() }) { raiseInContext(E) }")

        assertEquals(1, findings.size)
    }

    @Test
    fun ignoresHandlerThatDoesMoreThanRaise() {
        val findings = lint("fun Raise<E>.g(): Int = catch({ plainCall() }) { println(it); raise(E) }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresHandlerThatReturnsAValue() {
        val findings = lint("fun g(): Int = catch({ plainCall() }) { 0 }")

        assertEquals(0, findings.size)
    }

    // Its equivalent is Either.catchOrThrow, not Either.catch
    @Test
    fun ignoresReifiedCatch() {
        val findings = lint("fun Raise<E>.g(): Int = catch({ plainCall() }) { e: IOException -> raise(E) }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresEitherCatch() {
        val findings = lint("fun f() = either<E, Int> { Either.catch { plainCall() }.mapLeft { E }.bind() }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresCatchThatIsNotArrows() {
        val findings = lint(
            """
            fun Raise<E>.g(): Int {
                fun <A> catch(block: () -> A, handler: (Throwable) -> A): A = block()
                return catch({ plainCall() }) { raise(E) }
            }
            """.trimIndent(),
        )

        assertEquals(0, findings.size)
    }
}
