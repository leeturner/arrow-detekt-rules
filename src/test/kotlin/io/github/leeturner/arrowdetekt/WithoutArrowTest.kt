package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.KotlinEnvironmentContainer
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

// Consumers may run the rules over modules without Arrow; a rule that throws there fails their whole detekt run
class WithoutArrowTest {
    private val env = createEnvironment().let { env ->
        KotlinEnvironmentContainer(env.javaSourceRoots, env.jvmClasspathRoots.filterNot { "arrow" in it.toString() })
    }

    // Look-alikes of Arrow's names, so each rule gets past its name filter and checks types
    private val code =
        """
        fun raise(e: Any): Nothing = throw IllegalStateException(e.toString())
        fun <T> catch(block: () -> T, handler: (Throwable) -> T): T = try { block() } catch (e: Exception) { handler(e) }
        class Box<A>(val a: A) { fun <B> fold(l: (A) -> B, r: (A) -> B): B = r(a) }
        class Left(val v: Any)
        class Right(val v: Any)

        fun String.f(x: Int?): Int {
            length
            if (x == null) raise(1)
            if (x > 1) raise(2) else plainCall()
            val nested = Box(Box(1))
            try { plainCall() } catch (e: Exception) { }
            runCatching { plainCall() }.getOrNull()
            catch({ plainCall() }) { raise(it) }
            Box(1).fold({ Left(it) }, { Right(it) })
            return x
        }
        fun plainCall(): Int = 1
        """.trimIndent()

    private fun <T> lint(rule: T): List<Finding> where T : Rule, T : RequiresAnalysisApi = rule.lintWithContext(env, code)

    @Test
    fun rulesRunWithoutArrowOnTheClasspath() {
        val findings = listOf(
            lint(NoCatchAllInRaiseScope(Config.empty)),
            lint(NoFoldBackIntoEither(Config.empty)),
            lint(NoNestedEither(Config.empty)),
            lint(NoUnboundEither(Config.empty)),
            lint(PreferEitherCatch(Config.empty)),
            lint(UseEnsure(Config.empty)),
            lint(UseEnsureNotNull(Config.empty)),
        ).flatten()

        assertEquals(emptyList(), findings)
    }
}
