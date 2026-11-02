package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression

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
        val type = analyze(expression) {
            bindableName(expression.expressionType)
                ?.takeIf { !expression.isUsedAsExpression }
                ?.takeIf { expression.containingKtFile.scopeContext(expression).implicitValues.any { it.type.isSubtypeOf(RAISE) } }
        } ?: return
        val message = "The $type from ${expression.text} is never bound, so its error is dropped. Use ${expression.text}.bind() instead."
        report(Finding(Entity.from(expression), message))
    }
}

private fun KaSession.bindableName(type: KaType?): String? = when {
    type == null -> null
    type is KaFunctionType && type.receiverType?.isSubtypeOf(RAISE) == true -> "Effect"
    else -> BINDABLE.firstOrNull { (_, id) -> type.isSubtypeOf(id) }?.first
}
