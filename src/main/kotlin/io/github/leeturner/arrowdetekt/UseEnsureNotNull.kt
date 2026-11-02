package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtIfExpression

/**
 * Flags `if (x == null) raise(e)` with no else and suggests `ensureNotNull(x) { e }`, which also smart-casts x.
 * `x ?: raise(e)` is idiomatic Kotlin and isn't flagged.
 */
class UseEnsureNotNull(config: Config) :
    Rule(config, "An if with no else that only raises when a value is null reads better as ensureNotNull(value) { error }."),
    RequiresAnalysisApi {

    override fun visitIfExpression(expression: KtIfExpression) {
        super.visitIfExpression(expression)
        val match = ifRaise(expression) ?: return
        val value = nullChecked(match.condition)?.text ?: return
        val message = "Use ${match.receiver}ensureNotNull($value) { ${match.error.text} } " +
            "instead of if (${match.condition.text}) ${match.raise.text}. It also smart-casts $value to non-null."
        report(Finding(Entity.from(expression), message))
    }
}
