package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Flags `.fold({ Left(f(it)) }, { Right(g(it)) })` on an Either and suggests `.mapLeft { f(it) }.map { g(it) }`. */
class NoFoldBackIntoEither(config: Config) :
    Rule(config, "A fold that wraps both sides back into an Either reads better as mapLeft and map."),
    RequiresAnalysisApi {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "fold") return
        val arguments = expression.valueArguments
        if (arguments.size != 2 || arguments.any { it.isNamed() }) return
        val (ifLeft, ifRight) = arguments.map {
            it.getArgumentExpression()?.let(KtPsiUtil::safeDeparenthesize) as? KtLambdaExpression ?: return
        }
        val left = ifLeft.lastStatement()?.let { wrappedValue(it, "Left") } ?: return
        val right = ifRight.lastStatement()?.let { wrappedValue(it, "Right") } ?: return
        if (!isEitherFold(expression)) return
        val fold = ".fold(${ifLeft.shortText()}, ${ifRight.shortText()})"
        val suggestion = mapping("mapLeft", ifLeft, left) + mapping("map", ifRight, right)
        val message = if (suggestion.isEmpty()) "Drop $fold, which returns the same Either." else "Use $suggestion instead of $fold."
        report(Finding(Entity.from(expression), message))
    }
}

private fun KtLambdaExpression.lastStatement() = bodyExpression?.statements?.lastOrNull()

private fun KtLambdaExpression.parameters() = functionLiteral.valueParameterList?.let { "${it.text} -> " }.orEmpty()

private fun KtLambdaExpression.shortText(): String {
    val statements = bodyExpression?.statements.orEmpty()
    return "{ ${parameters()}${statements.singleOrNull()?.text ?: "..."} }"
}

// ".mapLeft { f(it) }", or "" when the lambda wraps its parameter unchanged
private fun mapping(name: String, lambda: KtLambdaExpression, value: KtExpression): String {
    val single = lambda.bodyExpression?.statements?.size == 1
    if (single && value.text == (lambda.valueParameters.singleOrNull()?.name ?: "it")) return ""
    return ".$name { ${lambda.parameters()}${if (single) value.text else "..."} }"
}

/** The value [statement] wraps when it is `Left(value)`, `Either.Left(value)` or `value.left()` ([side] "Left"), else null. */
private fun wrappedValue(statement: KtExpression, side: String): KtExpression? {
    val qualified = statement as? KtQualifiedExpression
    val call = (qualified?.selectorExpression ?: statement) as? KtCallExpression ?: return null
    return analyze(call) {
        when (val symbol = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol) {
            is KaConstructorSymbol -> call.valueArguments.singleOrNull()?.getArgumentExpression()
                ?.takeIf { symbol.containingClassId == ClassId.fromString("arrow/core/Either.$side") }
            is KaNamedFunctionSymbol -> qualified?.receiverExpression
                ?.takeIf { symbol.callableId?.asSingleFqName()?.asString() == "arrow.core.${side.lowercase()}" }
            else -> null
        }
    }
}

// fakeOverrideOriginal: on an Either.Right the call resolves to Right.fold, inherited from Either
private fun isEitherFold(call: KtCallExpression): Boolean = analyze(call) {
    val symbol = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return@analyze false
    symbol.fakeOverrideOriginal.callableId?.asSingleFqName()?.asString() == "arrow.core.Either.fold"
}
