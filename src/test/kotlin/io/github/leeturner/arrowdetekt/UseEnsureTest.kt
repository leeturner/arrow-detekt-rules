package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Finding
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
    fun wrapsCompoundConditionInSuggestion() {
        val findings = lint("fun Raise<E>.g(n: Int) { if (n > 0) raise(E) }")

        assertEquals("Use ensure(!(n > 0)) { E } instead of if (n > 0) raise(E).", findings.single().message)
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
            "Use ensure(!(n < 0)) { E }, then the else branch, instead of if (n < 0) raise(E) else ….",
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
