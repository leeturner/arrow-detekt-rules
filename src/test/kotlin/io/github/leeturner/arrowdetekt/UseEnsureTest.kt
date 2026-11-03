package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.test.TestConfig
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

class UseEnsureTest {
    private val env = createEnvironment()
    private val rule = UseEnsure(Config.empty)

    private fun lint(body: String): List<Finding> = rule.lintWithContext(env, PRELUDE + body)

    @Test
    fun flagsIfRaise() {
        val findings = lint("fun f(c: Boolean) = either<E, Unit> { if (c) raise(E) }")

        assertEquals("Use ensure(!c) { E } instead of if (c) raise(E).", findings.single().message)
    }

    @Test
    fun flagsIfRaiseInBlock() {
        val findings = lint("fun f(c: Boolean) = either<E, Unit> { if (c) { raise(E) } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsInRaiseExtensionFunction() {
        val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) raise(E) }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsExplicitReceiver() {
        val findings = lint("fun g(r: Raise<E>, c: Boolean) { if (c) r.raise(E) }")

        assertEquals("Use r.ensure(!c) { E } instead of if (c) r.raise(E).", findings.single().message)
    }

    @Test
    fun flagsRaiseThroughContextParameter() {
        val findings = lint("context(r: Raise<E>) fun g(c: Boolean) { if (c) raiseInContext(E) }")

        assertEquals(1, findings.size)
    }

    @Test
    fun dropsNegationInSuggestion() {
        val findings = lint("fun Raise<E>.g(ok: Boolean) { if (!ok) raise(E) }")

        assertEquals("Use ensure(ok) { E } instead of if (!ok) raise(E).", findings.single().message)
    }

    @Test
    fun flipsEqualityInSuggestion() {
        val findings = lint("fun Raise<E>.g(a: Int, b: Int) { if (a == b) raise(E) }")

        assertEquals("Use ensure(a != b) { E } instead of if (a == b) raise(E).", findings.single().message)
    }

    @Test
    fun flipsInequalityInSuggestion() {
        val findings = lint("fun Raise<E>.g(a: Any, b: Any) { if (a !== b) raise(E) }")

        assertEquals("Use ensure(a === b) { E } instead of if (a !== b) raise(E).", findings.single().message)
    }

    @Test
    fun flipsTypeCheckInSuggestion() {
        val findings = lint("fun Raise<E>.g(x: Any) { if (x !is String) raise(E) }")

        assertEquals("Use ensure(x is String) { E } instead of if (x !is String) raise(E).", findings.single().message)
    }

    @Test
    fun flipsContainsInSuggestion() {
        val findings = lint("fun Raise<E>.g(n: Int, xs: List<Int>) { if (n in xs) raise(E) }")

        assertEquals("Use ensure(n !in xs) { E } instead of if (n in xs) raise(E).", findings.single().message)
    }

    @Test
    fun swapsStdlibEmptinessChecksInSuggestion() {
        val findings = lint(
            "fun Raise<E>.g(m: Map<String, Int>, l: List<Int>, s: String, a: Array<Int>) { " +
                "if (m.isEmpty()) raise(E); if (l.isNotEmpty()) raise(E); if (s.isBlank()) raise(E); " +
                "if (s.isNotBlank()) raise(E); if (s.isEmpty()) raise(E); if (a.isEmpty()) raise(E); if ((l.isEmpty())) raise(E) }",
        )

        assertEquals(
            listOf("m.isNotEmpty()", "l.isEmpty()", "s.isNotBlank()", "s.isBlank()", "s.isNotEmpty()", "a.isNotEmpty()", "l.isNotEmpty()"),
            findings.map { it.message.substringAfter("ensure(").substringBefore(") {") },
        )
    }

    @Test
    fun swapsAnyAndNoneKeepingTheLambda() {
        val findings = lint("fun Raise<E>.g(l: List<Int>) { if (l.any { it < 0 }) raise(E); if (l.none()) raise(E) }")

        assertEquals(
            listOf(
                "Use ensure(l.none { it < 0 }) { E } instead of if (l.any { it < 0 }) raise(E).",
                "Use ensure(l.any()) { E } instead of if (l.none()) raise(E).",
            ),
            findings.map { it.message },
        )
    }

    // an override of the stdlib's isEmpty is on a Collection, so isNotEmpty applies
    @Test
    fun swapsOverridesAndImplicitReceivers() {
        val findings = lint(
            "class Bag2 : Collection<Int> by emptyList()\n" +
                "fun Raise<E>.g(b: Bag2) { val a = ArrayList<Int>(); if (a.isEmpty()) raise(E); if (b.isEmpty()) raise(E) }\n" +
                "fun Raise<E>.h(l: List<Int>) { with(l) { if (isEmpty()) raise(E) } }",
        )

        assertEquals(
            listOf("a.isNotEmpty()", "b.isNotEmpty()", "isNotEmpty()"),
            findings.map { it.message.substringAfter("ensure(").substringBefore(") {") },
        )
    }

    // no isNotNullOrEmpty in the stdlib; ranges have isEmpty but no isNotEmpty; Bag has no isNotEmpty at all
    @Test
    fun keepsThePrefixWhenThereIsNoStdlibOpposite() {
        val findings = lint(
            "class Bag { fun isEmpty(): Boolean = true }\n" +
                "fun Raise<E>.g(s: String?, b: Bag, n: Int) { if (s.isNullOrEmpty()) raise(E); if (b.isEmpty()) raise(E); " +
                "if ((1..n).isEmpty()) raise(E) }",
        )

        assertEquals(
            listOf("!s.isNullOrEmpty()", "!b.isEmpty()", "!(1..n).isEmpty()"),
            findings.map { it.message.substringAfter("ensure(").substringBefore(") {") },
        )
    }

    // ensure(!(a && b)) reads worse than the if it replaces
    @Test
    fun ignoresCompoundConditionThatNeedsNegating() {
        val findings = lint(
            "fun Raise<E>.g(x: String?, l: List<Boolean>, a: Boolean, c: Boolean) { " +
                "if (x == null && l.all { !it }) raise(E); if (a || c) raise(E); if ((a && c)) raise(E) }",
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsCompoundConditionThatNeedsNoNegating() {
        val findings = lint(
            "fun Raise<E>.g(a: Boolean, c: Boolean) { if (!(a && c)) raise(E); if (a && c) plainCall() else raise(E) }",
        )

        assertEquals(
            listOf(
                "Use ensure(a && c) { E } instead of if (!(a && c)) raise(E).",
                "Use ensure(a && c) { E }, then the if branch, instead of if (a && c) … else raise(E).",
            ),
            findings.map { it.message },
        )
    }

    @Test
    fun flagsCompoundConditionWhenConfigured() {
        val findings = UseEnsure(TestConfig("reportCompoundConditions" to true))
            .lintWithContext(env, PRELUDE + "fun Raise<E>.g(a: Boolean, c: Boolean) { if ((a || c)) raise(E) }")

        assertEquals("Use ensure(!(a || c)) { E } instead of if ((a || c)) raise(E).", findings.single().message)
    }

    @Test
    fun flipsComparisonInSuggestion() {
        val findings = lint(
            "fun <T : Comparable<T>> Raise<E>.g(n: Int, s: String, a: T, b: T) { " +
                "if (n > 0) raise(E); if (s <= \"m\") raise(E); if (a < b) raise(E); if (n >= 2L) raise(E) }",
        )

        assertEquals(
            listOf("n <= 0", "s > \"m\"", "a >= b", "n < 2L"),
            findings.map { it.message.substringAfter("ensure(").substringBefore(") {") },
        )
    }

    // !(a > b) isn't a <= b when either is NaN, so comparisons of Double and Float stay wrapped
    @Test
    fun wrapsFloatingPointComparisonInSuggestion() {
        val findings = lint("fun Raise<E>.g(d: Double, f: Float, n: Int) { if (d > 0) raise(E); if (n < f) raise(E) }")

        assertEquals(
            listOf("!(d > 0)", "!(n < f)"),
            findings.map { it.message.substringAfter("ensure(").substringBefore(") {") },
        )
    }

    @Test
    fun flagsRaiseInThenWithElse() {
        val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) raise(E) else plainCall() }")

        assertEquals("Use ensure(!c) { E }, then the else branch, instead of if (c) raise(E) else ….", findings.single().message)
    }

    @Test
    fun flagsRaiseInElse() {
        val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) plainCall() else raise(E) }")

        assertEquals("Use ensure(c) { E }, then the if branch, instead of if (c) … else raise(E).", findings.single().message)
    }

    @Test
    fun flagsRaiseInElseBlock() {
        val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) { plainCall() } else { raise(E) } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsWhenOtherBranchHasSeveralStatements() {
        val findings = lint(
            "fun Raise<E>.g(c: Boolean) { if (c) raise(E) else { plainCall(); plainCall() }; " +
                "if (c) { plainCall(); plainCall() } else raise(E) }",
        )

        assertEquals(2, findings.size)
    }

    @Test
    fun flagsExplicitReceiverInElse() {
        val findings = lint("fun g(r: Raise<E>, c: Boolean) { if (c) plainCall() else r.raise(E) }")

        assertEquals("Use r.ensure(c) { E }, then the if branch, instead of if (c) … else r.raise(E).", findings.single().message)
    }

    @Test
    fun flagsValAssignedFromIf() {
        val findings = lint("fun Raise<E>.g(n: Int) { val x = if (n < 0) raise(E) else n; println(x) }")

        assertEquals(
            "Use ensure(n >= 0) { E }, then the else branch, instead of if (n < 0) raise(E) else ….",
            findings.single().message,
        )
    }

    @Test
    fun flagsReturnedIf() {
        val findings = lint("fun Raise<E>.g(n: Int): Int { return if (n < 0) raise(E) else n }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsIfAsLastLineOfEither() {
        val findings = lint("fun f(n: Int) = either<E, Int> { if (n < 0) raise(E) else n }")

        assertEquals(1, findings.size)
    }

    // the position check only applies to an if with an else
    @Test
    fun flagsNoElseIfInsideAnotherIf() {
        val findings = lint("fun Raise<E>.g(a: Boolean, c: Boolean) { if (a) if (c) raise(E) }")

        assertEquals(1, findings.size)
    }

    // moving the ensure above the line would raise even when x is set
    @Test
    fun ignoresIfAfterElvis() {
        val findings = lint("fun Raise<E>.g(x: Int?, c: Boolean): Int { val y = x ?: if (c) raise(E) else 1; return y }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresIfAsArgument() {
        val findings = lint("fun Raise<E>.g(c: Boolean) { println(if (c) raise(E) else 1) }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresExpressionBody() {
        val findings = lint("fun Raise<E>.g(c: Boolean): Int = if (c) raise(E) else 1")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresElseIfChain() {
        val findings = lint("fun Raise<E>.g(a: Boolean, c: Boolean) { if (a) plainCall() else if (c) raise(E) else plainCall() }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresBothBranchesRaising() {
        val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) raise(E) else raise(E) }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresElseBlockWithMoreThanTheRaise() {
        val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) plainCall() else { plainCall(); raise(E) } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresBlockWithMoreThanTheRaise() {
        val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) { plainCall(); raise(E) } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresRaiseThatIsNotArrows() {
        val findings = lint("fun raise(e: E) { }\nfun g(c: Boolean) { if (c) raise(E) }")

        assertEquals(0, findings.size)
    }

    // ensureNotNull is the better suggestion there; that's UseEnsureNotNull's job
    @Test
    fun ignoresNullCheck() {
        val findings = lint("fun Raise<E>.g(x: String?) { if (x == null) raise(E); if (null == x) raise(E) }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresNotNullCheckWithRaiseInElse() {
        val findings = lint("fun Raise<E>.g(x: String?) { if (x != null) println(x) else raise(E) }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresNullOnTheLeftWithRaiseInElse() {
        val findings = lint("fun Raise<E>.g(x: String?) { if (null != x) println(x) else raise(E) }")

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsEqualsNullWithRaiseInElse() {
        val findings = lint("fun Raise<E>.g(x: String?) { if (x == null) plainCall() else raise(E) }")

        assertEquals("Use ensure(x == null) { E }, then the if branch, instead of if (x == null) … else raise(E).", findings.single().message)
    }

    // inside ensure's lambda, e is still Exception: the smart cast from the condition doesn't reach it
    @Test
    fun ignoresErrorThatNeedsTheConditionsSmartCast() {
        val findings = lint("interface G\nfun Raise<G>.g(e: Exception) { if (e is G) raise(e) }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresErrorThatNeedsTheElseBranchsNullSmartCast() {
        val findings = lint("fun Raise<String>.g(x: String?) { if (x == null) plainCall() else raise(x) }")

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsTypeCheckTheErrorDoesNotUse() {
        val findings = lint("fun Raise<E>.g(x: Any) { if (x is String) raise(E) }")

        assertEquals(1, findings.size)
    }

    // ensure then a bare throw is a worse read than the two-way split
    @Test
    fun ignoresWhenElseBranchThrows() {
        val findings = lint("fun Raise<E>.g(ok: Boolean) { if (!ok) raise(E) else throw IllegalStateException() }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresWhenIfBranchThrows() {
        val findings = lint("fun Raise<E>.g(ok: Boolean) { if (ok) throw IllegalStateException() else raise(E) }")

        assertEquals(0, findings.size)
    }
}
