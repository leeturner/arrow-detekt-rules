package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

class NoFoldBackIntoEitherTest {
    private val env = createEnvironment()
    private val rule = NoFoldBackIntoEither(Config.empty)

    private fun lint(body: String): List<Finding> =
        rule.lintWithContext(env, PRELUDE + "class Failed(val e: E)\nfun g(s: String): Int = s.length\n" + body)

    @Test
    fun flagsFoldIntoLeftAndRight() {
        val findings = lint("fun f() = findUser(1).fold({ Left(Failed(it)) }, { Right(g(it)) })")

        assertEquals("Use .mapLeft { Failed(it) }.map { g(it) } instead of .fold({ Left(Failed(it)) }, { Right(g(it)) }).", findings.single().message)
    }

    @Test
    fun flagsLeftAndRightExtensions() {
        val findings = lint("fun f() = findUser(1).fold({ Failed(it).left() }, { g(it).right() })")

        assertEquals("Use .mapLeft { Failed(it) }.map { g(it) } instead of .fold({ Failed(it).left() }, { g(it).right() }).", findings.single().message)
    }

    @Test
    fun flagsQualifiedConstructorsAndTrailingLambda() {
        val findings = lint("fun f() = findUser(1).fold({ Either.Left(Failed(it)) }) { Either.Right(g(it)) }")

        assertEquals(1, findings.size)
    }

    @Test
    fun keepsNamedParametersInSuggestion() {
        val findings = lint("fun f() = findUser(1).fold({ e -> Left(Failed(e)) }, { s -> Right(g(s)) })")

        assertEquals("Use .mapLeft { e -> Failed(e) }.map { s -> g(s) } instead of .fold({ e -> Left(Failed(e)) }, { s -> Right(g(s)) }).", findings.single().message)
    }

    @Test
    fun dropsSideThatWrapsItsParameterUnchanged() {
        val findings = lint("fun f() = findUser(1).fold({ Left(it) }, { Right(g(it)) })")

        assertEquals("Use .map { g(it) } instead of .fold({ Left(it) }, { Right(g(it)) }).", findings.single().message)
    }

    @Test
    fun flagsFoldThatWrapsBothSidesUnchanged() {
        val findings = lint("fun f() = findUser(1).fold({ e -> e.left() }, { Right(it) })")

        assertEquals("Drop .fold({ e -> e.left() }, { Right(it) }), which returns the same Either.", findings.single().message)
    }

    @Test
    fun flagsMultiStatementLambdaWhoseLastExpressionWraps() {
        val findings = lint("fun f() = findUser(1).fold({ println(it); Left(Failed(it)) }, { Right(g(it)) })")

        assertEquals("Use .mapLeft { ... }.map { g(it) } instead of .fold({ ... }, { Right(g(it)) }).", findings.single().message)
    }

    @Test
    fun flagsFoldOnRightSubtype() {
        val findings = lint("fun f() = Either.Right(\"u\").fold({ Left(it) }, { Right(g(it)) })")

        assertEquals(1, findings.size)
    }

    @Test
    fun ignoresFoldToAValue() {
        val findings = lint("fun f() = findUser(1).fold({ 0 }, { g(it) })")

        assertEquals(0, findings.size)
    }

    // A recover, not a map
    @Test
    fun ignoresFoldThatWrapsBothSidesInRight() {
        val findings = lint("fun f(): Either<E, Int> = findUser(1).fold({ Right(0) }, { Right(g(it)) })")

        assertEquals(0, findings.size)
    }

    // A flatMap, not a map
    @Test
    fun ignoresRightSideThatReturnsAnEither() {
        val findings = lint("fun f() = findUser(1).fold({ Left(Failed(it)) }, { findUser(g(it)) })")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresNamedArguments() {
        val findings = lint("fun f() = findUser(1).fold(ifLeft = { Left(Failed(it)) }, ifRight = { Right(g(it)) })")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresFoldThatIsNotEithers() {
        val findings = lint(
            """
            class Box(val s: String) {
                fun <C> fold(ifLeft: (E) -> C, ifRight: (String) -> C): C = ifRight(s)
            }
            fun f() = Box("u").fold({ Left(it) }, { Right(g(it)) })
            """.trimIndent(),
        )

        assertEquals(0, findings.size)
    }
}
