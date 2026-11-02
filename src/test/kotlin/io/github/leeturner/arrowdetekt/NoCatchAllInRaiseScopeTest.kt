package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun flagsLabelledRunCatching() {
        val findings = lint("fun f() = either<E, String?> { runCatching block@{ findUser(1).bind() }.getOrNull() }")

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

    @Test
    fun flagsRethrowGuardedByUnrelatedType() {
        val findings = lint("fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { if (e is IOException) throw e } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsThrowThatOnlyRunsInsideALambda() {
        val findings = lint("fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { val t = { throw e } } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun ignoresRethrowGuardedByCancellation() {
        val findings = lint(
            "fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { if (e is CancellationException) throw e } }",
        )

        assertEquals(0, findings.size)
    }

    // G is an interface, so the raise is never a G and takes the else branch
    @Test
    fun ignoresRethrowInElseOfUnrelatedTypeCheck() {
        val findings = lint(
            "interface G\nobject K : G\n" +
                "fun Raise<G>.f(): Int = try { raise(K) } catch (e: Exception) { if (e is G) raise(e) else throw e }",
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresRethrowInWhenElseAfterUnrelatedType() {
        val findings = lint(
            "interface G\nobject K : G\n" +
                "fun Raise<G>.f(): Int = try { raise(K) } catch (e: Exception) { when (e) { is G -> raise(e); else -> throw e } }",
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsUnrelatedTypeCheckWhoseElseSwallows() {
        val findings = lint(
            "interface G\nobject K : G\n" +
                "fun Raise<G>.f(): Int = try { raise(K) } catch (e: Exception) { if (e is G) raise(e) else -1 }",
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun ignoresGuardedRethrowAfterOtherStatements() {
        val findings = lint(
            "fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { if (e is CancellationException) { println(e); throw e } } }",
        )

        assertEquals(0, findings.size)
    }

    // the raise matches the first entry, so the later rethrow never runs for it
    @Test
    fun flagsRethrowAfterAnEntryThatCatchesTheRaise() {
        val findings = lint(
            "fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { " +
                "when (e) { is IllegalStateException -> {}; is CancellationException -> throw e } } }",
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun runCatchingMessageDoesNotSuggestNonFatalOrThrow() {
        val findings = lint("fun f() = either<E, String?> { runCatching { findUser(1).bind() }.getOrNull() }")

        assertFalse("nonFatalOrThrow" in findings.single().message)
    }

    // A crossinline lambda can be stored, so it gets the same guess as a non-inline one
    @Test
    fun ignoresCrossinlineLambdaStoredForLater() {
        val findings = lint(
            """
            inline fun later(crossinline block: () -> Unit): Lazy<Unit> = lazy { block() }
            fun f() = either<E, Unit> { try { later { raise(E) } } catch (e: Exception) { } }
            """.trimIndent(),
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsCrossinlineLambdaRunDuringTheCall() {
        val findings = lint(
            """
            inline fun now(crossinline block: () -> Unit) { runNow { block() } }
            fun f() = either<E, Unit> { try { now { raise(E) } } catch (e: Exception) { } }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun ignoresGetterInLocalObject() {
        val findings = lint("fun f() = either<E, Unit> { try { object { val x: Int get() = raise(E) } } catch (e: Exception) { } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsPropertyInitializerInLocalObject() {
        val findings = lint("fun f() = either<E, Unit> { try { object { val x: Int = raise(E) } } catch (e: Exception) { } }")

        assertEquals(1, findings.size)
    }

    // Members declared outside Raise don't raise, even when called on one
    @Test
    fun ignoresAnyMemberCalledOnRaise() {
        val findings = lint("fun g(r: Raise<E>) { try { r.toString() } catch (e: Exception) { } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresSuperMemberInRaiseImplementation() {
        val findings = lint("abstract class R : Raise<E> { fun f() { try { super.toString() } catch (e: Exception) { } } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresInnerClassConstructorInRaiseImplementation() {
        val findings = lint("abstract class R : Raise<E> { inner class I; fun f() { try { I() } catch (e: Exception) { } } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsRaiseInRaiseImplementation() {
        val findings = lint("abstract class R : Raise<E> { fun f() { try { raise(E) } catch (e: Exception) { } } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsOwnMemberInRaiseImplementation() {
        val findings = lint(
            "abstract class R : Raise<E> { fun fail(): Nothing = raise(E); fun f() { try { fail() } catch (e: Exception) { } } }",
        )

        assertEquals(1, findings.size)
    }

    // Raise passed in as a parameter and entered with `with`
    @Test
    fun flagsRaiseEnteredWithWith() {
        val findings = lint("fun g(r: Raise<E>) { try { with(r) { raise(E) } } catch (e: Exception) { } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun ignoresWithOnRaiseOpenedInsideTry() {
        val findings = lint("fun f() = try { either<E, Unit> { with(this) { raise(E) } } } catch (e: Exception) { null }")

        assertEquals(0, findings.size)
    }

    // Explicit `this` receivers
    @Test
    fun ignoresLabelledThisOfScopeOpenedInsideTry() {
        val findings = lint("fun f() = try { either<E, Unit> { this@either.raise(E) } } catch (e: Exception) { null }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresPlainThisOfScopeOpenedInsideTry() {
        val findings = lint("fun f() = try { either<E, Unit> { this.raise(E) } } catch (e: Exception) { null }")

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsLabelledThisOfOuterScope() {
        val findings = lint("fun f() = either<E, Unit> { try { this@either.raise(E) } catch (e: Exception) { } }")

        assertEquals(1, findings.size)
    }

    // Nested catch-alls: only the innermost one that swallows is reported
    @Test
    fun reportsOnlyTheInnermostBroadCatch() {
        val code = "fun f() = either<E, Unit> {\ntry {\ntry { raise(E) } catch (e: Exception) { }\n} catch (e: Exception) { }\n}"

        val findings = lint(code)

        assertEquals(1, findings.size)
        assertEquals(PRELUDE.lines().size + 2, findings.single().entity.location.source.line)
    }

    @Test
    fun reportsRunCatchingNotTheTryAroundIt() {
        val findings = lint("fun f() = either<E, Unit> { try { runCatching { raise(E) } } catch (e: Exception) { } }")

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.startsWith("runCatching"))
    }

    @Test
    fun flagsOuterCatchWhenInnerCatchIsNarrow() {
        val findings = lint(
            "fun f() = either<E, Unit> { try { try { raise(E) } catch (e: IOException) { } } catch (e: Exception) { } }",
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsRunCatchingWithNamedBlockArgument() {
        val findings = lint("fun f() = either<E, Unit> { runCatching(block = { raise(E) }) }")

        assertEquals(1, findings.size)
    }

    // Code that doesn't run inside the try
    @Test
    fun ignoresRaiseInLazySequenceLambda() {
        val findings = lint(
            "fun f() = either<E, List<String>> { " +
                "try { listOf(1).asSequence().map { findUser(it).bind() } } catch (e: Exception) { emptySequence() }.toList() }",
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresRaiseInLocalFunction() {
        val findings = lint("fun f() = either<E, Unit> { try { fun h() { raise(E) } } catch (e: Exception) { } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresRaiseInStoredLambda() {
        val findings = lint("fun f() = either<E, Unit> { try { val t = { raise(E) } } catch (e: Exception) { } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresRethrowThroughLet() {
        val findings = lint("fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { e.let { throw it } } }")

        assertEquals(0, findings.size)
    }

    // Inside either { }, runCatching resolves to T.runCatching with the Raise as receiver; that isn't a raise
    @Test
    fun ignoresRunCatchingWithoutRaiseInsideTry() {
        val findings = lint("fun f() = either<E, Unit> { try { runCatching { plainCall() } } catch (e: Exception) { } }")

        assertEquals(0, findings.size)
    }

    // Second review
    @Test
    fun flagsRaiseInNonInlineLambdaThatRunsNow() {
        val findings = lint("fun f() = either<E, Unit> { try { runNow { raise(E) } } catch (e: Exception) { } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsRaiseInsideWithContext() {
        val findings = lint(
            "suspend fun f() = either<E, Unit> { try { withContext(Dispatchers.IO) { raise(E) } } catch (e: Exception) { } }",
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun ignoresRaiseInsideFlowBuilder() {
        val findings = lint("fun f() = either<E, Unit> { try { val fl = flow<Int> { raise(E) } } catch (e: Exception) { } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresRaiseInLazyInitializer() {
        val findings = lint("fun f() = either<E, Unit> { try { val l = lazy { raise(E) } } catch (e: Exception) { } }")

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsInvokingRaiseBlockParameter() {
        val findings = lint("fun Raise<E>.safely(block: Raise<E>.() -> Unit) { try { block() } catch (e: Exception) { } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun reportsOuterCatchWhenRunCatchingResultIsRethrown() {
        val findings = lint(
            "fun f() = either<E, Unit> { try { runCatching { raise(E) }.getOrThrow() } catch (e: Exception) { } }",
        )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.startsWith("Catching"))
    }

    @Test
    fun flagsBindInAnonymousFunctionPassedToInline() {
        val findings = lint(
            "fun f() = either<E, Unit> { try { listOf(1).forEach(fun(i: Int) { findUser(i).bind() }) } catch (e: Exception) { } }",
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun ignoresWhenGuardedRethrow() {
        val findings = lint(
            "fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { when (e) { is CancellationException -> throw e; else -> {} } } }",
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsSafeCallRunOnNullableRaise() {
        val findings = lint("fun g(r: Raise<E>?) { try { r?.run { raise(E) } } catch (e: Exception) { } }")

        assertEquals(1, findings.size)
    }

    // Behaviour that already worked, locked in
    @Test
    fun flagsWrappingRethrow() {
        val findings = lint("fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { throw RuntimeException(e) } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsCatchRuntimeException() {
        val findings = lint("fun f() = either<E, Unit> { try { raise(E) } catch (e: RuntimeException) { } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsCatchIllegalStateException() {
        val findings = lint("fun f() = either<E, Unit> { try { raise(E) } catch (e: IllegalStateException) { } }")

        assertEquals(1, findings.size)
    }

    @Test
    fun flagsRaiseThroughContextParameter() {
        val findings = lint("context(r: Raise<E>) fun g() { try { raiseInContext(E) } catch (e: Exception) { } }")

        assertEquals(1, findings.size)
    }
}
