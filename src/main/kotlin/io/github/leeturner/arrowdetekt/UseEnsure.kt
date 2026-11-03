package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

/**
 * Flags `if (cond) raise(e)` and suggests `ensure(!cond) { e }`. Also flags `if (cond) raise(e) else x` and
 * `if (cond) x else raise(e)` when the `if` is a statement, a `val`/`var` initializer or a `return` value, and
 * suggests `ensure` followed by the other branch.
 *
 * Not when the other branch throws, or the error relies on a smart cast from the `if`, which `ensure`'s lambda loses.
 *
 * Not when the condition would need negating and is an `&&` or `||` (`if (a && b) raise(e)`), since
 * `ensure(!(a && b))` reads worse than the `if`, unless [reportCompoundConditions] is set.
 *
 * Null checks (`if (x == null) raise(e)`) are left to [UseEnsureNotNull].
 */
class UseEnsure(config: Config) :
    Rule(config, "An if with no else that only raises reads better as ensure(condition) { error }."),
    RequiresAnalysisApi {

    @Configuration("Also report `if (a && b) raise(e)` and `if (a || b) raise(e)`, suggesting a negated compound condition.")
    private val reportCompoundConditions: Boolean by config(false)

    override fun visitIfExpression(expression: KtIfExpression) {
        super.visitIfExpression(expression)
        val match = ifRaise(expression) ?: return
        if (ensuredNotNull(match) != null) return
        if (!match.raisesInElse && !reportCompoundConditions && isCompound(match.condition)) return
        val condition = if (match.raisesInElse) match.condition.text else negate(match.condition)
        val message = "Use ${match.receiver}ensure($condition) { ${match.error.text} }${match.followedBy} " +
            "instead of ${match.quoted}."
        report(Finding(Entity.from(expression), message))
    }
}

private fun isCompound(condition: KtExpression): Boolean =
    (KtPsiUtil.safeDeparenthesize(condition) as? KtBinaryExpression)?.operationToken.let { it == KtTokens.ANDAND || it == KtTokens.OROR }

private fun negate(parenthesized: KtExpression): String {
    val condition = KtPsiUtil.safeDeparenthesize(parenthesized)
    return swapToOpposite(condition) ?: when (condition) {
        is KtPrefixExpression if condition.operationToken == KtTokens.EXCL ->
            condition.baseExpression?.let { KtPsiUtil.safeDeparenthesize(it).text } ?: "!${condition.text}"
        is KtBinaryExpression if condition.operationToken in FLIPPED ->
            "${condition.left?.text} ${FLIPPED[condition.operationToken]} ${condition.right?.text}"
        is KtBinaryExpression if condition.operationToken in COMPARISONS && !isFloatingPoint(condition) ->
            "${condition.left?.text} ${COMPARISONS[condition.operationToken]} ${condition.right?.text}"
        is KtIsExpression -> "${condition.leftHandSide.text} ${if (condition.isNegated) "is" else "!is"} ${condition.typeReference?.text}"
        is KtSimpleNameExpression, is KtCallExpression, is KtQualifiedExpression ->
            "!${condition.text}"
        else -> "!(${condition.text})"
    }
}

// `x.isEmpty()` negates to `x.isNotEmpty()`, but only for the stdlib's (or an override of it, like ArrayList's): a
// user's own isEmpty may have no opposite, and ranges have isEmpty but no isNotEmpty
private fun swapToOpposite(condition: KtExpression): String? {
    val call = condition as? KtCallExpression
        ?: (condition as? KtDotQualifiedExpression)?.selectorExpression as? KtCallExpression ?: return null
    val callee = call.calleeExpression ?: return null
    val opposite = OPPOSITES[callee.text] ?: return null
    val isStdlib = analyze(call) {
        val symbol = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return@analyze false
        (sequenceOf(symbol) + symbol.allOverriddenSymbols).any { it.callableId?.packageName?.asString() in STDLIB_PACKAGES }
    }
    if (!isStdlib) return null
    val start = callee.textRange.startOffset - condition.textRange.startOffset
    return condition.text.replaceRange(start, start + callee.textLength, opposite)
}

private val OPPOSITES = mapOf(
    "isEmpty" to "isNotEmpty", "isNotEmpty" to "isEmpty", "isBlank" to "isNotBlank", "isNotBlank" to "isBlank",
    "any" to "none", "none" to "any",
)

private val STDLIB_PACKAGES = setOf("kotlin.collections", "kotlin.text", "kotlin.sequences")

// Operators whose negation is another operator, by definition: `a != b` is `!(a == b)`.
private val FLIPPED = mapOf(
    KtTokens.EQEQ to "!=", KtTokens.EXCLEQ to "==", KtTokens.EQEQEQ to "!==", KtTokens.EXCLEQEQEQ to "===",
    KtTokens.IN_KEYWORD to "!in", KtTokens.NOT_IN to "in",
)

// `!(a > b)` is `a <= b`, except for a Double or Float, where both are false when either is NaN
private val COMPARISONS = mapOf(
    KtTokens.LT to ">=", KtTokens.GTEQ to "<", KtTokens.GT to "<=", KtTokens.LTEQ to ">",
)

private fun isFloatingPoint(comparison: KtBinaryExpression): Boolean = analyze(comparison) {
    listOfNotNull(comparison.left, comparison.right).any { operand ->
        val type = operand.expressionType?.lowerBoundIfFlexible() ?: return@analyze true
        type.isDoubleType || type.isFloatType
    }
}
