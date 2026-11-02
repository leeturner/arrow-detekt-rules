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
    fun ignoresIfWithElse() {
        val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) raise(E) else plainCall() }")

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
}
