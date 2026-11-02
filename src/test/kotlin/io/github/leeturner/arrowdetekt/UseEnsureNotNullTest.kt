package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

class UseEnsureNotNullTest {
    private val env = createEnvironment()
    private val rule = UseEnsureNotNull(Config.empty)

    private fun lint(body: String): List<Finding> = rule.lintWithContext(env, PRELUDE + body)

    @Test
    fun flagsNullCheckThenRaise() {
        val findings = lint("fun f(x: String?) = either<E, Unit> { if (x == null) raise(E) }")

        assertEquals(
            "Use ensureNotNull(x) { E } instead of if (x == null) raise(E). It also smart-casts x to non-null.",
            findings.single().message,
        )
    }

    @Test
    fun flagsNullOnTheLeft() {
        val findings = lint("fun Raise<E>.g(x: String?) { if (null == x) { raise(E) } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsExplicitReceiver() {
        val findings = lint("fun g(r: Raise<E>, x: String?) { if (x == null) r.raise(E) }")

        assertEquals(
            "Use r.ensureNotNull(x) { E } instead of if (x == null) r.raise(E). It also smart-casts x to non-null.",
            findings.single().message,
        )
    }

    @Test
    fun flagsRaiseThroughContextParameter() {
        val findings = lint("context(r: Raise<E>) fun g(x: String?) { if (x == null) raiseInContext(E) }")

        assertEquals(1, findings.size)
    }

    @Test
    fun ignoresElvisRaise() {
        val findings = lint("fun Raise<E>.g(x: String?): String = x ?: raise(E)")

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsNullCheckWithElse() {
        val findings = lint("fun Raise<E>.g(x: String?) { if (x == null) raise(E) else println(x) }")

        assertEquals(
            "Use ensureNotNull(x) { E }, then the else branch, instead of if (x == null) raise(E) else …. " +
                "It also smart-casts x to non-null.",
            findings.single().message,
        )
    }

    @Test
    fun flagsNotNullCheckWithRaiseInElse() {
        val findings = lint("fun Raise<E>.g(x: String?) { if (x != null) println(x) else raise(E) }")

        assertEquals(
            "Use ensureNotNull(x) { E }, then the if branch, instead of if (x != null) … else raise(E). " +
                "It also smart-casts x to non-null.",
            findings.single().message,
        )
    }

    @Test
    fun flagsWhenOtherBranchHasSeveralStatements() {
        val findings = lint(
            "fun Raise<E>.g(x: String?) { if (x != null) { println(x); println(x) } else raise(E); " +
                "if (x == null) raise(E) else { println(x); println(x) } }",
        )

        assertEquals(2, findings.size)
    }

    @Test
    fun flagsNullOnTheLeftWithRaiseInElse() {
        val findings = lint("fun Raise<E>.g(x: String?) { if (null != x) println(x) else raise(E) }")

        assertEquals(1, findings.size)
    }

    // raises when x is not null, so it's an ensure, not an ensureNotNull
    @Test
    fun ignoresEqualsNullWithRaiseInElse() {
        val findings = lint("fun Raise<E>.g(x: String?) { if (x == null) plainCall() else raise(E) }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresConditionThatIsNotANullCheck() {
        val findings = lint("fun Raise<E>.g(x: String?) { if (x != null) raise(E) }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresRaiseThatIsNotArrows() {
        val findings = lint("fun raise(e: E) { }\nfun g(x: String?) { if (x == null) raise(E) }")

        assertEquals(0, findings.size)
    }
}
