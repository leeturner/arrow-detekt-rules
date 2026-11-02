package io.github.leeturner.arrowdetekt

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.contextParameters
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

internal val RAISE = ClassId.fromString("arrow/core/raise/Raise")

/**
 * The Raise values [call] raises into: its receivers and context arguments whose declared type is a Raise.
 *
 * Checks the callee's declared receivers, not the values passed: inside `either { }` the generic
 * `T.runCatching { }` gets the Raise as its receiver, but it doesn't raise.
 */
@OptIn(KaExperimentalApi::class) // contextArguments
internal fun KaSession.raiseReceivers(call: KaFunctionCall<*>): List<KaReceiverValue> {
    val symbol = call.symbol
    val extension = call.extensionReceiver
        ?.takeIf { (symbol.receiverParameter?.returnType ?: it.type).isSubtypeOf(RAISE) }
    val context = call.contextArguments.zip(symbol.contextParameters)
        .filter { (_, parameter) -> parameter.returnType.isSubtypeOf(RAISE) }
        .map { (argument, _) -> argument }
    return listOfNotNull(call.dispatchReceiver?.takeIf { it.type.isSubtypeOf(RAISE) }, extension) + context
}

/** An `if` with no else whose only statement is Arrow's `raise(error)`, called on [receiver] (`r.`, or empty). */
internal class IfRaise(val condition: KtExpression, val raise: KtExpression, val receiver: String, val error: KtExpression)

internal fun ifRaise(expression: KtIfExpression): IfRaise? {
    if (expression.`else` != null) return null
    val condition = expression.condition ?: return null
    val then = expression.then.let { (it as? KtBlockExpression)?.statements?.singleOrNull() ?: it } ?: return null
    val error = raisedError(then) ?: return null
    val receiver = (then as? KtQualifiedExpression)?.let { it.receiverExpression.text + it.operationTokenNode.text }.orEmpty()
    return IfRaise(condition, then, receiver, error)
}

/** The error [expression] raises when it is Arrow's `raise(error)` or `r.raise(error)`, else null. */
internal fun raisedError(expression: KtExpression): KtExpression? {
    val call = ((expression as? KtQualifiedExpression)?.selectorExpression ?: expression) as? KtCallExpression ?: return null
    val error = call.valueArguments.singleOrNull()?.getArgumentExpression() ?: return null
    return error.takeIf { isArrowRaise(call) }
}

private fun isArrowRaise(call: KtCallExpression): Boolean = analyze(call) {
    val resolved = call.resolveToCall()?.successfulFunctionCallOrNull() ?: return@analyze false
    resolved.symbol.callableId?.callableName?.asString() == "raise" && raiseReceivers(resolved).isNotEmpty()
}

/** `x` when [condition] is `x == null` or `null == x`, else null. */
internal fun nullChecked(condition: KtExpression): KtExpression? {
    val check = condition as? KtBinaryExpression ?: return null
    if (check.operationToken != KtTokens.EQEQ) return null
    return when {
        check.right?.text == "null" -> check.left
        check.left?.text == "null" -> check.right
        else -> null
    }
}
