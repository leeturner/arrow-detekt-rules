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

    // the onLeft can't fall through, so the Left never gets past it
    @Test
    fun ignoresOnLeftThatExits() {
        val findings = lint(
            "fun f() = either<String, Unit> { findUser(1).onLeft { raise(\"bad\") }.onRight { println(it) } }\n" +
                "fun g() = either<E, Int> { findUser(1).onRight { println(it) }.onLeft { return@either 0 }; 1 }\n" +
                "fun Raise<E>.h() { findUser(1).onLeft { println(it); throw IllegalStateException() } }\n" +
                "fun Raise<E>.i(o: arrow.core.Option<Int>) { o.onNone { raise(E) } }",
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsOnLeftThatMayFallThrough() {
        val findings = lint(
            "fun Raise<E>.g(c: Boolean) { findUser(1).onLeft { if (c) raise(it) }; findUser(1).onRight { raise(E) } }",
        )

        assertEquals(2, findings.size)
    }

    // flatMap's own Left isn't the one onLeft saw
    @Test
    fun flagsOnLeftThatExitsBeforeAFlatMap() {
        assertEquals(1, lint("fun Raise<E>.g() { findUser(1).onLeft { raise(it) }.flatMap { findUser(2) } }").size)
    }

    @Test
    fun leavesAnnotationsOutOfTheMessage() {
        val findings = lint("fun Raise<E>.g(n: Int?) {\n    @Suppress(\"x\")\n    findUser(n!!)\n}")

        assertEquals(
            "The Either from findUser(n!!) is never bound, so its error is dropped. Use findUser(n!!).bind() instead.",
            findings.single().message,
        )
    }

    // onRight returns r unchanged, and r is bound afterwards, so nothing is dropped
    @Test
    fun ignoresPeekOnVariableBoundLater() {
        assertEquals(0, lint("fun f() = either<E, Int> { val r = findUser(1); r.onRight { println(it) }; r.bind(); 1 }").size)
    }

    @Test
    fun ignoresPeekOnVariableReturnedLater() {
        assertEquals(0, lint("fun Raise<E>.g(): Either<E, String> { val r = findUser(1); r.onLeft { println(it) }; return r }").size)
    }

    @Test
    fun ignoresPeekOnParameterBoundLater() {
        assertEquals(0, lint("fun Raise<E>.g(r: Either<E, String>) { r.onRight { println(it) }; r.bind() }").size)
    }

    @Test
    fun ignoresChainedPeeksOnVariableBoundLater() {
        assertEquals(0, lint("fun Raise<E>.g(r: Either<E, String>) { r.onRight { println(it) }.onLeft { println(it) }; r.bind() }").size)
    }

    @Test
    fun flagsPeekOnVariableNeverUsedAgain() {
        assertEquals(1, lint("fun f() = either<E, Int> { val r = findUser(1); r.onRight { println(it) }; 1 }").size)
    }

    @Test
    fun flagsPeekOnVariableUsedOnlyBefore() {
        assertEquals(1, lint("fun f() = either<E, Int> { val r = findUser(1); println(r); r.onRight { println(it) }; 1 }").size)
    }

    // map isn't a peek: its result is a different Either
    @Test
    fun flagsMapOnVariableBoundLater() {
        assertEquals(1, lint("fun f() = either<E, Int> { val r = findUser(1); r.map { it.length }; r.bind(); 1 }").size)
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
    fun suggestsSafeBindForNullable() {
        val findings = lint("fun Raise<E>.g(id: Int?) { id?.let { findUser(it) } }")

        assertEquals("Use id?.let { findUser(it) }?.bind() instead.", findings.single().message.substringAfter("dropped. "))
    }

    // .bind() on the end would bind only raise(E)
    @Test
    fun wrapsOperatorExpressionInParentheses() {
        val findings = lint("fun Raise<E>.g(a: Either<E, String>?) { a ?: raise(E) }")

        assertEquals("Use (a ?: raise(E)).bind() instead.", findings.single().message.substringAfter("dropped. "))
    }

    @Test
    fun quotesFirstLineOfMultiLineChain() {
        val findings = lint(
            """
            fun f() = either<E, Int> {
                findUser(1)
                    .map { it.length }
                1
            }
            """.trimIndent(),
        )

        assertEquals("The Either from findUser(1)… is never bound, so its error is dropped. Add .bind() to the end.", findings.single().message)
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
