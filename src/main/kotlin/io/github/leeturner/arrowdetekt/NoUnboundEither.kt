package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtAnnotatedExpression
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

private val BINDABLE = listOf("Either", "Option", "Ior").map { it to ClassId.fromString("arrow/core/$it") }

/** Flags an Either, Option, Ior or Effect used as a statement while a Raise is in scope: the `bind()` is missing, so its error is dropped. */
class NoUnboundEither(config: Config) :
    Rule(config, "An Either, Option, Ior or Effect left unbound inside a Raise scope drops its error."),
    RequiresAnalysisApi {

    @OptIn(KaExperimentalApi::class) // scopeContext
    override fun visitExpression(expression: KtExpression) {
        super.visitExpression(expression)
        // whole statements and if/when branches only, so a chain is reported once; containers are reported through their branches
        if (expression.parent !is KtBlockExpression && expression.parent !is KtContainerNodeForControlStructureBody && expression.parent !is KtWhenEntry) return
        if (expression is KtBlockExpression || expression is KtIfExpression || expression is KtWhenExpression || expression is KtTryExpression) return
        val message = analyze(expression) {
            val type = expression.expressionType ?: return@analyze null
            bindableName(type)
                ?.takeIf { !expression.isUsedAsExpression && !peeksAtValueUsedLater(expression) && !exitsOnError(expression) }
                ?.takeIf { expression.containingKtFile.scopeContext(expression).implicitValues.any { it.type.isSubtypeOf(RAISE) } }
                ?.let { message(it, expression, if (type.isNullable) "?.bind()" else ".bind()") }
        } ?: return
        report(Finding(Entity.from(expression), message))
    }
}

private fun message(type: String, statement: KtExpression, bind: String): String {
    // `@Suppress("…") x` binds as x.bind(): the annotation stays where it is
    val expression = generateSequence(statement) { (it as? KtAnnotatedExpression)?.baseExpression }.last()
    val lines = expression.text.lines()
    val start = "The $type from ${lines.first()}${if (lines.size > 1) "…" else ""} is never bound, so its error is dropped."
    // a postfix .bind() on anything else (a ?: b, x as T, l@ x) would bind only its last part
    val postfix = expression is KtCallExpression || expression is KtQualifiedExpression || expression is KtNameReferenceExpression ||
        expression is KtParenthesizedExpression || expression is KtArrayAccessExpression
    return when {
        lines.size > 1 -> "$start ${if (postfix) "Add" else "Wrap it in parentheses and add"} $bind to the end."
        postfix -> "$start Use ${expression.text}$bind instead."
        else -> "$start Use (${expression.text})$bind instead."
    }
}

private fun KaSession.bindableName(type: KaType): String? = when {
    type is KaFunctionType && type.receiverType?.isSubtypeOf(RAISE) == true -> "Effect"
    else -> BINDABLE.firstOrNull { (_, id) -> type.isSubtypeOf(id) }?.first
}

private val PEEKS = setOf("arrow.core.Either.onRight", "arrow.core.Either.onLeft", "arrow.core.Option.onSome", "arrow.core.Option.onNone")

// `r.onRight { }` hands back r itself, so when r is used later its error isn't dropped here
private fun KaSession.peeksAtValueUsedLater(expression: KtExpression): Boolean {
    var receiver = expression
    while (receiver is KtQualifiedExpression && isPeek(receiver)) receiver = receiver.receiverExpression
    if (receiver === expression || receiver !is KtNameReferenceExpression) return false
    val name = receiver.getReferencedName()
    val symbol = receiver.mainReference.resolveToSymbol() ?: return false
    return expression.containingKtFile.collectDescendantsOfType<KtNameReferenceExpression> {
        it.textOffset > expression.textRange.endOffset && it.getReferencedName() == name
    }.any { it.mainReference.resolveToSymbol() == symbol }
}

// `x.onLeft { raise(it) }` can't fall through, so no Left gets past it; peeks after it hand back the same value
// ponytail: only peeks after it, so `x.onLeft { raise(it) }.map { }` is still reported; follow map too if that turns up
private fun KaSession.exitsOnError(expression: KtExpression): Boolean {
    var call = expression
    while (call is KtQualifiedExpression) {
        val name = peekName(call)?.takeIf { it in PEEKS } ?: return false
        if (name in ERROR_PEEKS && lambdaExits(call.selectorExpression as KtCallExpression)) return true
        call = call.receiverExpression
    }
    return false
}

private fun KaSession.lambdaExits(call: KtCallExpression): Boolean {
    val lambda = call.valueArguments.lastOrNull()?.getArgumentExpression() as? KtLambdaExpression ?: return false
    return lambda.bodyExpression?.statements?.lastOrNull()?.expressionType?.isNothingType == true
}

private val ERROR_PEEKS = setOf("arrow.core.Either.onLeft", "arrow.core.Option.onNone")

private fun KaSession.peekName(call: KtQualifiedExpression): String? =
    (call.selectorExpression as? KtCallExpression)?.resolveToCall()?.successfulFunctionCallOrNull()
        ?.symbol?.callableId?.asSingleFqName()?.asString()

private fun KaSession.isPeek(call: KtQualifiedExpression): Boolean = peekName(call) in PEEKS

