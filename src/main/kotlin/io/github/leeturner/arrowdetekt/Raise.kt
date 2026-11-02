package io.github.leeturner.arrowdetekt

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.contextParameters
import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

internal val RAISE = ClassId.fromString("arrow/core/raise/Raise")

/**
 * The Raise values [call] raises into: its receivers and context arguments whose declared type is a Raise.
 *
 * Checks the callee's declared receivers and declaring class, not the values passed: inside `either { }` the generic
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
    // the class declaring the member, not the receiver's type: `r.toString()` is Any's, so it doesn't raise
    val declaringClass = symbol.fakeOverrideOriginal.containingDeclaration as? KaClassSymbol
    val dispatch = call.dispatchReceiver?.takeIf { declaringClass?.defaultType?.isSubtypeOf(RAISE) == true }
    return listOfNotNull(dispatch, extension) + context
}

/**
 * An `if` where one branch is only Arrow's `raise(error)`, called on [receiver] (`r.`, or empty).
 * [quoted] is the `if` as messages show it, and [followedBy] says where the other branch goes ("" with no else).
 */
internal class IfRaise(
    val condition: KtExpression,
    val receiver: String,
    val error: KtExpression,
    val raisesInElse: Boolean,
    val quoted: String,
    val followedBy: String,
)

/**
 * `if (c) raise(e)`, `if (c) raise(e) else x` or `if (c) x else raise(e)`. With an else, only when the `if` is a
 * statement, a `val`/`var` initializer or a `return` value, where `ensure` then the other branch is a plain rewrite.
 */
internal fun ifRaise(expression: KtIfExpression): IfRaise? = matchIfRaise(expression)?.takeUnless { needsSmartCast(it.error) }

// Inside ensure's lambda the error loses any smart cast the if gave it: `if (e is G) raise(e)` becomes
// `ensure(e !is G) { e }`, where e is no longer a G.
// ponytail: also skips smart casts made before the if, which would survive into the lambda
private fun needsSmartCast(error: KtExpression): Boolean = analyze(error) {
    (error.collectDescendantsOfType<KtExpression>() + error).any { it.smartCastInfo != null }
}

private fun matchIfRaise(expression: KtIfExpression): IfRaise? {
    val condition = expression.condition ?: return null
    // only the raising branch has to be a single statement; the other can be any block
    val then = expression.then?.let(::single)
    val otherwise = expression.`else`?.let(::single)
    val thenError = then?.let(::raisedError)
    if (expression.`else` == null) {
        if (then == null || thenError == null) return null
        return IfRaise(condition, receiverOf(then), thenError, false, "if (${condition.text}) ${then.text}", "")
    }
    if (!isPlainStatement(expression)) return null
    val elseError = otherwise?.let(::raisedError)
    return when {
        thenError != null && elseError != null -> null
        // raise one way, throw the other: a two-way error split, not a guard, and ensure then a bare throw reads worse
        then is KtThrowExpression || otherwise is KtThrowExpression -> null
        then != null && thenError != null -> IfRaise(
            condition, receiverOf(then), thenError, false,
            "if (${condition.text}) ${then.text} else …", ", then the else branch,",
        )
        otherwise != null && elseError != null -> IfRaise(
            condition, receiverOf(otherwise), elseError, true,
            "if (${condition.text}) … else ${otherwise.text}", ", then the if branch,",
        )
        else -> null
    }
}

private fun single(branch: KtExpression): KtExpression? = if (branch is KtBlockExpression) branch.statements.singleOrNull() else branch

private fun receiverOf(raise: KtExpression): String =
    (raise as? KtQualifiedExpression)?.let { it.receiverExpression.text + it.operationTokenNode.text }.orEmpty()

private fun isPlainStatement(expression: KtIfExpression): Boolean = when (val parent = expression.parent) {
    is KtBlockExpression -> true
    is KtProperty -> parent.initializer == expression
    is KtReturnExpression -> parent.returnedExpression == expression
    else -> false
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

/** `x` when [match] raises exactly when x is null: `if (x == null) raise(e)` or `if (x != null) … else raise(e)`. */
internal fun ensuredNotNull(match: IfRaise): KtExpression? =
    nullChecked(match.condition, if (match.raisesInElse) KtTokens.EXCLEQ else KtTokens.EQEQ)

/** `x` when [condition] is `x <operator> null` or `null <operator> x`, else null. */
private fun nullChecked(condition: KtExpression, operator: KtSingleValueToken): KtExpression? {
    val check = condition as? KtBinaryExpression ?: return null
    if (check.operationToken != operator) return null
    return when {
        check.right?.text == "null" -> check.left
        check.left?.text == "null" -> check.right
        else -> null
    }
}
