package io.github.leeturner.arrowdetekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance

private val EITHER = ClassId.fromString("arrow/core/Either")

/** Flags a property or function whose declared or inferred type is `Either<E, Either<...>>`, which usually means a missing `bind()`. */
class NoNestedEither(config: Config) :
    Rule(config, "An Either inside an Either usually means a missing bind() or a map that should be a flatMap."),
    RequiresAnalysisApi {

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        check(property)
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        check(function)
    }

    @OptIn(KaExperimentalApi::class) // render
    private fun check(declaration: KtCallableDeclaration) {
        val type = analyze(declaration) {
            declaration.returnType.takeIf { isNestedEither(it) }
                ?.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
        } ?: return
        val message = "${declaration.name} has type $type, an Either inside an Either. " +
            "Use flatMap instead of map, or bind() the inner Either inside either { }."
        report(Finding(Entity.atName(declaration), message))
    }
}

// Checks the Either supertype, so Either.Right<Either<...>> and type aliases count; Nothing is a subtype of Either but isn't one
private fun KaSession.isNestedEither(type: KaType): Boolean {
    val either = (listOf(type) + type.allSupertypes).firstOrNull { (it as? KaClassType)?.classId == EITHER } as? KaClassType
    val right = either?.typeArguments?.getOrNull(1)?.type ?: return false
    return !right.isNothingType && right.isSubtypeOf(EITHER)
}
