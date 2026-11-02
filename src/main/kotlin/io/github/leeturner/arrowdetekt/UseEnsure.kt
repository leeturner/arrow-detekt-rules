package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

/**
 * Flags `if (cond) raise(e)` with no else and suggests `ensure(!cond) { e }`.
 *
 * Null checks (`if (x == null) raise(e)`) are left alone: `ensureNotNull(x) { e }` is the better fix there.
 */
class UseEnsure(config: Config) :
    Rule(config, "An if with no else that only raises reads better as ensure(condition) { error }."),
    RequiresAnalysisApi {

    override fun visitIfExpression(expression: KtIfExpression) {
        super.visitIfExpression(expression)
        if (expression.`else` != null) return
        val condition = expression.condition ?: return
        if (isNullCheck(condition)) return
        val then = expression.then.let { (it as? KtBlockExpression)?.statements?.singleOrNull() ?: it } ?: return
        val qualified = then as? KtQualifiedExpression
        val call = (qualified?.selectorExpression ?: then) as? KtCallExpression ?: return
        val error = call.valueArguments.singleOrNull()?.getArgumentExpression() ?: return
        if (!isArrowRaise(call)) return
        val receiver = qualified?.let { it.receiverExpression.text + it.operationTokenNode.text }.orEmpty()
        val message = "Use ${receiver}ensure(${negate(condition)}) { ${error.text} } instead of if (${condition.text}) ${then.text}."
        report(Finding(Entity.from(expression), message))
    }
}

private fun isArrowRaise(call: KtCallExpression): Boolean = analyze(call) {
    val resolved = call.resolveToCall()?.successfulFunctionCallOrNull() ?: return@analyze false
    resolved.symbol.callableId?.callableName?.asString() == "raise" && raiseReceivers(resolved).isNotEmpty()
}

private fun isNullCheck(condition: KtExpression): Boolean =
    condition is KtBinaryExpression && condition.operationToken == KtTokens.EQEQ &&
        (condition.left?.text == "null" || condition.right?.text == "null")

private fun negate(condition: KtExpression): String = when (condition) {
    is KtPrefixExpression if condition.operationToken == KtTokens.EXCL ->
        condition.baseExpression?.let { KtPsiUtil.safeDeparenthesize(it).text } ?: "!${condition.text}"
    is KtSimpleNameExpression, is KtCallExpression, is KtQualifiedExpression, is KtParenthesizedExpression ->
        "!${condition.text}"
    else -> "!(${condition.text})"
}
