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
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaReceiverParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.contextParameters
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * Flags a catch-all wrapped around a call that raises into an outer Raise scope. The catch-all
 * swallows Arrow's RaiseCancellationException, so the raised error never reaches the scope.
 *
 * When catch-alls nest, only the innermost one the raise escapes through is reported; fixing it
 * exposes the next one out.
 */
class NoCatchAllInRaiseScope(config: Config) :
    Rule(
        config,
        "A catch-all around a raising call swallows Arrow's RaiseCancellationException, so the error is lost.",
    ),
    RequiresAnalysisApi {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val body = swallowingRunCatchingBody(expression) ?: return
        if (containsEscapingRaiseCall(body)) {
            report(Finding(Entity.from(expression), RUN_CATCHING_MESSAGE))
        }
    }

    override fun visitTryExpression(expression: KtTryExpression) {
        super.visitTryExpression(expression)
        val clauses = swallowingClauses(expression)
        if (clauses.isEmpty() || !containsEscapingRaiseCall(expression.tryBlock)) return
        clauses.forEach { clause ->
            val type = clause.catchParameter?.typeReference?.text
            report(Finding(Entity.from(clause), catchMessage(type)))
        }
    }

    private companion object {
        fun catchMessage(type: String?) =
            "Catching $type here swallows Arrow's RaiseCancellationException, so the raise never reaches its " +
                "Raise scope. Catch a narrower type, use Either.catch, or rethrow with nonFatalOrThrow()."

        const val RUN_CATCHING_MESSAGE =
            "runCatching catches Arrow's RaiseCancellationException, so the raise never reaches its Raise scope. " +
                "Use Either.catch instead."
    }
}

private val RAISE = ClassId.fromString("arrow/core/raise/Raise")
private val RAISE_CANCELLATION = ClassId.fromString("arrow/core/raise/RaiseCancellationException")

// ---- Catch-alls ------------------------------------------------------------------------------

/** The block of a `runCatching` call, trailing or named, or null when [call] isn't one. */
private fun runCatchingBody(call: KtCallExpression): KtExpression? {
    if (call.calleeExpression?.text != "runCatching") return null // cheap filter before resolving
    val isRunCatching = analyze(call) {
        call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId?.asSingleFqName()
            ?.asString() == "kotlin.runCatching"
    }
    if (!isRunCatching) return null
    return call.valueArguments.firstNotNullOfOrNull { (it.getArgumentExpression() as? KtLambdaExpression)?.bodyExpression }
}

/** Like [runCatchingBody], but null when the result goes straight to `getOrThrow()`, which rethrows. */
private fun swallowingRunCatchingBody(call: KtCallExpression): KtExpression? {
    val whole = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == call } ?: call
    val next = whole.parent as? KtQualifiedExpression
    if (next?.receiverExpression == whole && next.selectorExpression?.text == "getOrThrow()") return null
    return runCatchingBody(call)
}

/** Catch clauses of [expression] that would swallow a raise. */
private fun swallowingClauses(expression: KtTryExpression): List<KtCatchClause> =
    expression.catchClauses.filter { swallowsRaise(it) && !rethrows(it) }

private fun swallowsRaise(clause: KtCatchClause): Boolean =
    clause.catchParameter?.typeReference?.let(::coversRaise) ?: false

// True when a value of [typeReference]'s type could be a RaiseCancellationException
private fun coversRaise(typeReference: KtTypeReference): Boolean =
    analyze(typeReference) { buildClassType(RAISE_CANCELLATION).isSubtypeOf(typeReference.type) }

// Only rethrows that always run for the raise count: a direct statement of the catch block, or one
// guarded by `if (e is T)` where T covers the raise. A throw in some other branch or a lambda doesn't.
private fun rethrows(clause: KtCatchClause): Boolean {
    val name = clause.catchParameter?.name ?: return false
    val statements = (clause.catchBody as? KtBlockExpression)?.statements ?: return false
    return statements.any { rethrowsDirectly(it, name) || rethrowsWhenRaise(it, name) }
}

// ponytail: matches the exception by name; aliasing it through a val isn't followed
private fun rethrowsDirectly(statement: KtExpression?, name: String): Boolean = when (statement) {
    is KtThrowExpression -> statement.thrownExpression?.text == name
    is KtBlockExpression -> rethrowsDirectly(statement.statements.singleOrNull(), name)
    is KtDotQualifiedExpression -> statement.receiverExpression.text == name && rethrowsThroughSelector(statement)
    else -> false
}

// `e.nonFatalOrThrow()`, or a scope function whose block rethrows: `e.let { throw it }`, `e.run { throw this }`
private fun rethrowsThroughSelector(expression: KtDotQualifiedExpression): Boolean {
    val selector = expression.selectorExpression as? KtCallExpression ?: return false
    val alias = when (selector.calleeExpression?.text) {
        "nonFatalOrThrow" -> return selector.valueArguments.isEmpty()
        "let", "also" -> selector.lambdaArguments.singleOrNull()?.getLambdaExpression()
            ?.valueParameters?.singleOrNull()?.name ?: "it"
        "run", "apply" -> "this"
        else -> return false
    }
    val block = selector.lambdaArguments.singleOrNull()?.getLambdaExpression()?.bodyExpression ?: return false
    return block.statements.any { rethrowsDirectly(it, alias) }
}

// `if (e is T) throw e`, `when (e) { is T -> throw e }` or `when { e is T -> throw e }`, with T covering the raise
private fun rethrowsWhenRaise(statement: KtExpression, name: String): Boolean = when (statement) {
    is KtIfExpression -> isRaiseCheck(statement.condition, name) && rethrowsDirectly(statement.then, name)
    is KtWhenExpression -> statement.entries.any { entry ->
        rethrowsDirectly(entry.expression, name) && entry.conditions.any { condition ->
            when (condition) {
                is KtWhenConditionIsPattern -> statement.subjectExpression?.text == name && !condition.isNegated &&
                    condition.typeReference?.let(::coversRaise) == true
                is KtWhenConditionWithExpression -> isRaiseCheck(condition.expression, name)
                else -> false
            }
        }
    }
    else -> false
}

private fun isRaiseCheck(condition: KtExpression?, name: String): Boolean {
    val check = condition as? KtIsExpression ?: return false
    return !check.isNegated && check.leftHandSide.text == name && check.typeReference?.let(::coversRaise) == true
}

// ---- Raising calls ---------------------------------------------------------------------------

/**
 * True when [body] holds a call that, while [body] runs, raises into a Raise scope from outside
 * [body], and no catch-all nested inside [body] already swallows it.
 */
internal fun containsEscapingRaiseCall(body: KtElement): Boolean =
    body.collectDescendantsOfType<KtCallExpression>().any { call ->
        val between = generateSequence(call.parent) { it.parent }.takeWhile { it != body }.toList()
        between.all(::runsInPlace) && between.none(::isInnerCatchAllBody) && raisesOutside(call, body)
    }

// Local functions and stored lambdas run later. A lambda or anonymous function passed as an argument
// is assumed to run during the call (inline functions, withContext, transaction, ...).
private fun runsInPlace(element: PsiElement): Boolean = when (element) {
    is KtFunctionLiteral -> argumentRunsInPlace(element)
    is KtNamedFunction -> element.nameIdentifier == null && argumentRunsInPlace(element)
    else -> true
}

/** The call [function] is passed to, with the argument expression holding it; null when it isn't an argument. */
private fun argumentOf(function: KtFunction): Pair<KtCallExpression, KtExpression>? {
    val value = if (function is KtFunctionLiteral) function.parent as? KtLambdaExpression ?: return null else function
    val argument = value.getStrictParentOfType<KtValueArgument>() ?: return null
    val expression = argument.getArgumentExpression() ?: return null
    if (KtPsiUtil.safeDeparenthesize(expression) != value) return null
    val call = argument.getStrictParentOfType<KtCallExpression>() ?: return null
    return call to expression
}

// ponytail: guesses from the result type; a non-inline helper that stores the lambda and returns
// something else (a callback registry, a Thread) is treated as running it now
private fun argumentRunsInPlace(function: KtFunction): Boolean {
    val (call, expression) = argumentOf(function) ?: return false
    return analyze(call) {
        val resolved = call.resolveToCall()?.successfulFunctionCallOrNull() ?: return@analyze true
        val parameter = resolved.valueArgumentMapping[expression]?.symbol
        val inlined = (resolved.symbol as? KaNamedFunctionSymbol)?.isInline == true && parameter?.isNoinline == false
        inlined || LAZY_RESULTS.none { resolved.signature.returnType.isSubtypeOf(it) }
    }
}

// Results that hold on to the lambda and run it later: `asSequence().map { }`, `flow { }`, `lazy { }`
private val LAZY_RESULTS = listOf("kotlin/sequences/Sequence", "kotlinx/coroutines/flow/Flow", "kotlin/Lazy", "kotlin/Function")
    .map(ClassId::fromString)

private fun isInnerCatchAllBody(element: PsiElement): Boolean {
    val parent = element.parent
    return when {
        parent is KtTryExpression && parent.tryBlock == element -> swallowingClauses(parent).isNotEmpty()
        parent is KtFunctionLiteral ->
            argumentOf(parent)?.let { (call, _) -> swallowingRunCatchingBody(call) == element } ?: false
        else -> false
    }
}

// Checks the callee's declared receivers, not the values passed: inside `either { }` the generic
// `T.runCatching { }` gets the Raise as its receiver, but it doesn't raise.
@OptIn(KaExperimentalApi::class) // contextArguments
private fun raisesOutside(call: KtCallExpression, body: KtElement): Boolean = analyze(call) {
    val resolved = call.resolveToCall()?.successfulFunctionCallOrNull() ?: return@analyze false
    val symbol = resolved.symbol
    val extension = resolved.extensionReceiver
        ?.takeIf { (symbol.receiverParameter?.returnType ?: it.type).isSubtypeOf(RAISE) }
    val context = resolved.contextArguments.zip(symbol.contextParameters)
        .filter { (_, parameter) -> parameter.returnType.isSubtypeOf(RAISE) }
        .map { (argument, _) -> argument }
    (listOfNotNull(resolved.dispatchReceiver?.takeIf { it.type.isSubtypeOf(RAISE) }, extension) + context)
        .any { !providedInside(it, body) }
}

// ---- Where a Raise comes from ----------------------------------------------------------------

private fun KaSession.providedInside(receiver: KaReceiverValue, body: KtElement): Boolean =
    when (val value = (receiver as? KaSmartCastedReceiverValue)?.original ?: receiver) {
        is KaImplicitReceiverValue -> declaredInside(value.symbol, body)
        is KaExplicitReceiverValue -> expressionProvidedInside(value.expression, body)
        else -> false
    }

// `this`, `this@label` or a name: follow it to its declaration. Anything else counts as outside.
private fun KaSession.expressionProvidedInside(expression: KtExpression, body: KtElement): Boolean {
    val symbol = when (expression) {
        is KtThisExpression -> expression.instanceReference.mainReference.resolveToSymbol()
        is KtNameReferenceExpression -> expression.mainReference.resolveToSymbol()
        else -> null
    } ?: return false
    return declaredInside(symbol, body)
}

// ponytail: a local `val r = this` inside [body] counts as inside even if `this` is an outer scope
private fun KaSession.declaredInside(symbol: KaSymbol, body: KtElement): Boolean {
    val declaration = (symbol as? KaReceiverParameterSymbol)?.owningCallableSymbol?.psi ?: symbol.psi ?: return false
    if (!PsiTreeUtil.isAncestor(body, declaration, false)) return false
    return declaration !is KtFunctionLiteral || opensFreshRaise(declaration, body)
}

// A lambda opens its own Raise scope (`either { }`, `recover { }`) unless the call it is passed to
// hands it an existing Raise: as a receiver (`r.run { }`, `T.runCatching { }` inside a Raise scope)
// or an argument (`with(r) { }`). Then the Raise comes from wherever that one does.
private fun KaSession.opensFreshRaise(literal: KtFunctionLiteral, body: KtElement): Boolean {
    val (call, _) = argumentOf(literal) ?: return true
    val resolved = call.resolveToCall()?.successfulFunctionCallOrNull() ?: return true
    val receivers = listOfNotNull(resolved.dispatchReceiver, resolved.extensionReceiver).filter { it.type.isSubtypeOf(RAISE) }
    val arguments = call.valueArguments.mapNotNull { it.getArgumentExpression() }
        .filter { it !is KtLambdaExpression && it.expressionType?.isSubtypeOf(RAISE) == true }
    return receivers.all { providedInside(it, body) } && arguments.all { expressionProvidedInside(it, body) }
}
