package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtPsiUtil

/**
 * Flags Arrow's `catch({ ... }) { raise(f(it)) }` and suggests `Either.catch { ... }.mapLeft { f(it) }.bind()`.
 *
 * The reified `catch<T, _>` isn't flagged: its equivalent is `Either.catchOrThrow`, not `Either.catch`.
 */
class PreferEitherCatch(config: Config) :
    Rule(config, "A catch whose handler only raises reads better as Either.catch { ... }.mapLeft { error }.bind()."),
    RequiresAnalysisApi {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "catch") return
        val arguments = expression.valueArguments
        if (arguments.size != 2 || arguments.any { it.isNamed() }) return
        val (block, handler) = arguments.map { it.getArgumentExpression()?.let(KtPsiUtil::safeDeparenthesize) ?: return }
        val lambda = handler as? KtLambdaExpression ?: return
        val raise = lambda.bodyExpression?.statements?.singleOrNull() ?: return
        val error = raisedError(raise) ?: return
        if (!isArrowCatch(expression)) return
        val parameters = lambda.functionLiteral.valueParameterList?.let { "${it.text} -> " }.orEmpty()
        val catching = if (block is KtLambdaExpression) " { ... }" else "(${block.text})"
        val original = if (block is KtLambdaExpression) "{ ... }" else block.text
        val message = "Use Either.catch$catching.mapLeft { $parameters${error.text} }.bind() " +
            "instead of catch($original) { $parameters${raise.text} }."
        report(Finding(Entity.from(expression), message))
    }
}

// The top-level catch(block, handler) catching Throwable: no receiver (Effect.catch) and not the reified overload
private fun isArrowCatch(call: KtCallExpression): Boolean = analyze(call) {
    val symbol = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol as? KaNamedFunctionSymbol ?: return@analyze false
    symbol.callableId?.asSingleFqName()?.asString() == "arrow.core.raise.catch" &&
        symbol.receiverParameter == null && symbol.typeParameters.size == 1
}
