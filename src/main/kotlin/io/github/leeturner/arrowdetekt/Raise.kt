package io.github.leeturner.arrowdetekt

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.contextParameters
import org.jetbrains.kotlin.name.ClassId

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
