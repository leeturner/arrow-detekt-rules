package io.github.leeturner.arrowdetekt

import dev.detekt.api.RuleSetProvider
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals

class ArrowRuleSetProviderTest {
    @Test
    fun providerIsRegistered() {
        val provider = ServiceLoader.load(RuleSetProvider::class.java).single { it.ruleSetId.value == "arrow" }

        assertEquals(listOf("NoCatchAllInRaiseScope"), provider.instance().rules.keys.map { it.value })
    }
}
