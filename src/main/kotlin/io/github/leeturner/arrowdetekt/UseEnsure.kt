package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
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
 * Null checks (`if (x == null) raise(e)`) are left to [UseEnsureNotNull].
 */
class UseEnsure(config: Config) :
    Rule(config, "An if with no else that only raises reads better as ensure(condition) { error }."),
    RequiresAnalysisApi {

    override fun visitIfExpression(expression: KtIfExpression) {
        super.visitIfExpression(expression)
        val match = ifRaise(expression) ?: return
        if (ensuredNotNull(match) != null) return
        val condition = if (match.raisesInElse) match.condition.text else negate(match.condition)
        val message = "Use ${match.receiver}ensure($condition) { ${match.error.text} }${match.followedBy} " +
            "instead of ${match.quoted}."
        report(Finding(Entity.from(expression), message))
    }
}

private fun negate(condition: KtExpression): String = when (condition) {
    is KtPrefixExpression if condition.operationToken == KtTokens.EXCL ->
        condition.baseExpression?.let { KtPsiUtil.safeDeparenthesize(it).text } ?: "!${condition.text}"
    is KtSimpleNameExpression, is KtCallExpression, is KtQualifiedExpression, is KtParenthesizedExpression ->
        "!${condition.text}"
    else -> "!(${condition.text})"
}
