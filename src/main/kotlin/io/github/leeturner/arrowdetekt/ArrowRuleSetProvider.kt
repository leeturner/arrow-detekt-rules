package io.github.leeturner.arrowdetekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class ArrowRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("arrow")

    override fun instance() = RuleSet(ruleSetId, listOf(::NoCatchAllInRaiseScope, ::NoFoldBackIntoEither, ::PreferEitherCatch, ::UseEnsure, ::UseEnsureNotNull))
}
