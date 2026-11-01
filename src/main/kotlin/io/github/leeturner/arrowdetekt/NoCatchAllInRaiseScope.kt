package io.github.leeturner.arrowdetekt

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaReceiverParameterSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Flags a catch-all wrapped around a call that raises into an outer Raise scope. The catch-all
 * swallows Arrow's RaiseCancellationException, so the raised error never reaches the scope.
 */
class NoCatchAllInRaiseScope(config: Config) :
    Rule(
        config,
        "A catch-all around a raising call swallows Arrow's RaiseCancellationException, so the error is lost.",
    ),
    RequiresAnalysisApi {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val body = expression.lambdaArguments.firstOrNull()?.getLambdaExpression()?.bodyExpression ?: return
        val isRunCatching = analyze(expression) {
            expression.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId?.asSingleFqName()
                ?.asString() == "kotlin.runCatching"
        }
        if (isRunCatching && containsEscapingRaiseCall(body)) {
            report(Finding(Entity.from(expression), RUN_CATCHING_MESSAGE))
        }
    }

    override fun visitTryExpression(expression: KtTryExpression) {
        super.visitTryExpression(expression)
        if (!containsEscapingRaiseCall(expression.tryBlock)) return
        expression.catchClauses
            .filter { swallowsRaise(it) && !rethrows(it) }
            .forEach { clause ->
                val type = clause.catchParameter?.typeReference?.text
                report(Finding(Entity.from(clause), catchMessage(type)))
            }
    }

    private fun swallowsRaise(clause: KtCatchClause): Boolean {
        val typeReference = clause.catchParameter?.typeReference ?: return false
        return analyze(typeReference) {
            buildClassType(RAISE_CANCELLATION).isSubtypeOf(typeReference.type)
        }
    }

    private fun rethrows(clause: KtCatchClause): Boolean {
        val name = clause.catchParameter?.name ?: return false
        val body = clause.catchBody ?: return false
        return body.collectDescendantsOfType<KtThrowExpression>().any { it.thrownExpression?.text == name } ||
            body.collectDescendantsOfType<KtDotQualifiedExpression>().any {
                it.receiverExpression.text == name && it.selectorExpression?.text == "nonFatalOrThrow()"
            }
    }

    private companion object {
        val RAISE_CANCELLATION = ClassId.fromString("arrow/core/raise/RaiseCancellationException")

        fun catchMessage(type: String?) =
            "Catching $type here swallows Arrow's RaiseCancellationException, so the raise never reaches its " +
                "Raise scope. Catch a narrower type, use Either.catch, or rethrow with nonFatalOrThrow()."

        const val RUN_CATCHING_MESSAGE =
            "runCatching catches Arrow's RaiseCancellationException, so the raise never reaches its Raise scope. " +
                "Use Either.catch, or rethrow with nonFatalOrThrow()."
    }
}

private val RAISE = ClassId.fromString("arrow/core/raise/Raise")

/** True when [body] holds a call whose Raise receiver comes from outside [body]. */
@OptIn(KaExperimentalApi::class) // contextArguments
internal fun containsEscapingRaiseCall(body: KtElement): Boolean =
    body.collectDescendantsOfType<KtCallExpression>().any { call ->
        analyze(call) {
            val applied = call.resolveToCall()?.successfulFunctionCallOrNull() ?: return@analyze false
            (listOfNotNull(applied.dispatchReceiver, applied.extensionReceiver) + applied.contextArguments)
                .any { it.type.isSubtypeOf(RAISE) && !providedInside(it, body) }
        }
    }

// Explicit receivers (`r.raise(e)`) count as outside: we can't cheaply tell where the value came from
private fun KaSession.providedInside(receiver: KaReceiverValue, body: KtElement): Boolean {
    val value = (receiver as? KaSmartCastedReceiverValue)?.original ?: receiver
    if (value !is KaImplicitReceiverValue) return false
    val symbol = value.symbol
    val declaration: PsiElement? = (symbol as? KaReceiverParameterSymbol)?.owningCallableSymbol?.psi ?: symbol.psi
    return declaration != null && PsiTreeUtil.isAncestor(body, declaration, false)
}
