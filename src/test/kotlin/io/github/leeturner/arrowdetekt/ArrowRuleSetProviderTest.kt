package io.github.leeturner.arrowdetekt

import dev.detekt.api.RuleSetProvider
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals

class ArrowRuleSetProviderTest {
    @Test
    fun providerIsRegistered() {
        val provider = ServiceLoader.load(RuleSetProvider::class.java).single { it.ruleSetId.value == "arrow" }

        assertEquals(listOf("NoCatchAllInRaiseScope", "PreferEitherCatch", "UseEnsure", "UseEnsureNotNull"), provider.instance().rules.keys.map { it.value })
    }

    // detekt-core merges every plugin's config/config.yml into its defaults; without it every rule is off
    @Test
    fun defaultConfigActivatesEveryRule() {
        val yml = javaClass.classLoader.getResource("config/config.yml")?.readText()

        assertEquals("arrow:\n  NoCatchAllInRaiseScope:\n    active: true\n  PreferEitherCatch:\n    active: true\n  UseEnsure:\n    active: true\n  UseEnsureNotNull:\n    active: true\n", yml)
    }
}
