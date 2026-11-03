package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.createEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

class NoNestedEitherTest {
    private val env = createEnvironment()
    private val rule = NoNestedEither(Config.empty)

    private fun lint(body: String): List<Finding> = rule.lintWithContext(env, PRELUDE + body)

    @Test
    fun flagsInferredFunctionReturnType() {
        val findings = lint("fun f() = findUser(1).map { findUser(it.length) }")

        assertEquals(
            "f has type Either<E, Either<E, String>>, an Either inside an Either. " +
                "Use flatMap instead of map, or bind() the inner Either inside either { }.",
            findings.single().message,
        )
    }

    // the README's escape hatch for deliberate nesting
    @Test
    fun ignoresSuppressedDeclaration() {
        val findings = lint("@Suppress(\"NoNestedEither\")\nfun f() = findUser(1).map { findUser(it.length) }")

        assertEquals(0, findings.size)
    }

    @Test
    fun flagsDeclaredFunctionReturnType() {
        val findings = lint("fun f(): Either<E, Either<E, String>> = Either.Right(findUser(1))")

        assertEquals(1, findings.size)
    }

    // The missing bind(): findUser(1) is returned from either { } instead of bound
    @Test
    fun flagsEitherReturnedFromEitherBuilder() {
        val findings = lint("fun f() = either<E, _> { findUser(1) }")

        assertEquals("f has type Either<E, Either<E, String>>", findings.single().message.substringBefore(", an Either"))
    }

    @Test
    fun flagsInferredLocalProperty() {
        val findings = lint("fun f() = either<E, Unit> { val user = findUser(1).map { findUser(it.length) } }")

        assertEquals("user has type Either<E, Either<E, String>>", findings.single().message.substringBefore(", an Either"))
    }

    @Test
    fun flagsRightSubtypeAndTypeAlias() {
        val findings = lint(
            """
            typealias Result<A> = Either<E, A>
            val a = Either.Right(findUser(1))
            val b: Result<Result<String>> = Either.Right(findUser(1))
            """.trimIndent(),
        )

        assertEquals(2, findings.size)
    }

    @Test
    fun ignoresFlatMap() {
        val findings = lint("fun f() = findUser(1).flatMap { findUser(it.length) }")

        assertEquals(0, findings.size)
    }

    @Test
    fun ignoresEitherOnTheLeftOrInsideAnotherType() {
        val findings = lint(
            """
            val a: Either<Either<E, String>, Int> = Either.Right(1)
            val b: Either<E, List<Either<E, String>>> = Either.Right(listOf(findUser(1)))
            val c = listOf(findUser(1))
            """.trimIndent(),
        )

        assertEquals(0, findings.size)
    }

    // Right(x) on its own is Either<Nothing, ...>; Nothing is a subtype of Either but isn't one
    @Test
    fun ignoresNothingOnTheRight() {
        val findings = lint("val a = Either.Left(E)")

        assertEquals(0, findings.size)
    }
}
