package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

class NoUnboundEitherTest {
    private val env = createEnvironment()
    private val rule = NoUnboundEither(Config.empty)

    private fun lint(body: String): List<Finding> = rule.lintWithContext(env, PRELUDE + body)

    @Test
    fun flagsEitherStatement() {
        val findings = lint("fun f() = either<E, Int> { findUser(1); 1 }")

        assertEquals(
            "The Either from findUser(1) is never bound, so its error is dropped. Use findUser(1).bind() instead.",
            findings.single().message,
        )
    }

    @Test
    fun namesOptionIorAndEffect() {
        val findings = lint(
            """
            fun f() = either<E, Int> {
                arrow.core.Some(1)
                arrow.core.Ior.Right(1)
                arrow.core.raise.effect<E, Int> { 1 }
                arrow.core.raise.eagerEffect<E, Int> { 1 }
                1
            }
            """.trimIndent(),
        )

        assertEquals(listOf("The Option", "The Ior", "The Effect", "The Effect"), findings.map { it.message.substringBefore(" from") })
    }

    @Test
    fun flagsRaiseExtensionFunction() {
        assertEquals(1, lint("fun Raise<E>.g() { findUser(1) }").size)
    }

    @Test
    fun flagsContextParameterFunction() {
        assertEquals(1, lint("context(r: Raise<E>) fun g() { findUser(1) }").size)
    }

    @Test
    fun flagsInsideNestedLambda() {
        assertEquals(1, lint("fun f() = either<E, Unit> { listOf(1).forEach { findUser(it) } }").size)
    }

    @Test
    fun flagsInsideLocalFunction() {
        assertEquals(1, lint("fun f() = either<E, Int> { fun load() { findUser(1) }; load(); 1 }").size)
    }

    @Test
    fun flagsLastLineOfUnitLambda() {
        assertEquals(1, lint("fun f() = either<E, Unit> { findUser(1) }").size)
    }

    @Test
    fun flagsIfAndWhenBranches() {
        val findings = lint(
            """
            fun Raise<E>.g(c: Boolean, n: Int) {
                if (c) findUser(1)
                if (c) { findUser(2) }
                when (n) {
                    1 -> findUser(3)
                    else -> {}
                }
            }
            """.trimIndent(),
        )

        assertEquals(3, findings.size)
    }

    // onLeft passes the Either on, so the error is still dropped
    @Test
    fun flagsOnLeftSideEffect() {
        assertEquals(1, lint("fun f() = either<E, Int> { findUser(1).onLeft { println(it) }; 1 }").size)
    }

    // The if itself has type Either and is unused too; only its branches are reported
    @Test
    fun reportsBranchesNotTheIfAroundThem() {
        val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) findUser(1) else findUser(2) }")

        assertEquals(listOf("findUser(1)", "findUser(2)"), findings.map { it.message.substringAfter("from ").substringBefore(" is never") })
    }

    @Test
    fun reportsChainOnce() {
        val findings = lint("fun f() = either<E, Int> { findUser(1).map { it.length }; 1 }")

        assertEquals("Use findUser(1).map { it.length }.bind() instead.", findings.single().message.substringAfter("dropped. "))
    }

    @Test
    fun ignoresBound() {
        assertEquals(0, lint("fun f() = either<E, Int> { findUser(1).bind(); 1 }").size)
    }

    @Test
    fun ignoresStored() {
        assertEquals(0, lint("fun f() = either<E, Int> { val u = findUser(1); u.bind().length }").size)
    }

    @Test
    fun ignoresFolded() {
        assertEquals(0, lint("fun f() = either<E, Int> { findUser(1).fold({ 0 }, { it.length }); 1 }").size)
    }

    @Test
    fun ignoresReturnedAsValue() {
        assertEquals(0, lint("fun f() = either<E, _> { findUser(1) }").size)
    }

    @Test
    fun ignoresLambdaResult() {
        assertEquals(0, lint("fun f() = either<E, Int> { val load = { findUser(1) }; load().bind().length }").size)
    }

    @Test
    fun ignoresOutsideRaiseScope() {
        assertEquals(0, lint("fun g() { findUser(1) }").size)
    }

    @Test
    fun ignoresResult() {
        assertEquals(0, lint("fun f() = either<E, Int> { runCatching { plainCall() }; 1 }").size)
    }

    // raise(E) and TODO() have type Nothing, a subtype of Either
    @Test
    fun ignoresNothingStatements() {
        assertEquals(0, lint("fun Raise<E>.g(c: Boolean) { if (c) raise(E); if (!c) TODO() }").size)
    }
}
