package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.bridge.bridgeJson
import com.nousresearch.hermes.bridge.protocol.ModelOptionProvider
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// What the picker shows is mapped once from the core's row: prices as its strings, locks, limits and usage, a past time dropped.
class ModelFactsTest {
    private companion object {
        const val NOW = 1_794_729_600_000L  // 2026-11-15T08:00:00Z
        val ROME: TimeZone = TimeZone.getTimeZone("Europe/Rome")
    }

    /** One `model.options` row as the core answers it; [fields] are the row's JSON members after its models. */
    private fun row(models: String, fields: String = "") =
        bridgeJson.decodeFromString(ModelOptionProvider.serializer(),
                                    """{"slug":"p","name":"P","models":$models${if (fields.isEmpty()) "" else ",$fields"}}""")

    private fun facts(models: String, fields: String = "") = ProviderFacts.of(row(models, fields), NOW)

    @Test
    fun pricesAreTheCoresOwnStrings() {
        val shown = facts("""["a","b","c"]""", """"pricing":{
            "a":{"input":"$3.00","output":"$15.00","free":false},
            "b":{"input":"$0.0005","output":"$0.0005","free":false},
            "c":{"input":"free","output":"","free":true}}""").models

        assertEquals(ModelFacts(input = "$3.00", output = "$15.00"), shown["a"])
        // A sub-cent price the core widened (models_pricing.py:124-127): re-formatting it would print $0.00.
        assertEquals(ModelFacts(input = "$0.0005", output = "$0.0005"), shown["b"])
        assertEquals(ModelFacts(input = "free", output = null, free = true), shown["c"])
    }

    @Test
    fun aModelWithNoPriceHasNone() {
        assertEquals(emptyMap<String, ModelFacts>(), facts("""["a"]""").models)
        assertEquals(emptyMap<String, ModelFacts>(), facts("""["a"]""", """"pricing":{"other":{"input":"$1.00","output":"$2.00","free":false}}""").models)
        // The core's "" is unknown, not zero.
        assertEquals(emptyMap<String, ModelFacts>(), facts("""["a"]""", """"pricing":{"a":{"input":"","output":"","free":false}}""").models)
    }

    @Test
    fun theModelsTheCoreMarksUnavailableAreLocked() {
        val nous = facts("""["nous/hermes-4","nous/hermes-4-405b"]""", """"unavailable_models":["nous/hermes-4-405b"]""").models

        assertTrue(nous.getValue("nous/hermes-4-405b").locked)
        assertFalse((nous["nous/hermes-4"] ?: ModelFacts()).locked)

        // The cached-only path: the plan is not known yet, so the core locks every model and says why (inventory.py:900-907).
        val pending = facts("""["nous/hermes-4","nous/hermes-4-405b"]""", """"free_tier_pending":true,
            "unavailable_models":["nous/hermes-4","nous/hermes-4-405b"],
            "warning":"Checking Nous plan entitlement… models unlock on the next picker open or refresh."""")
        assertEquals(listOf(true, true), pending.models.values.map { it.locked })
        assertEquals("Checking Nous plan entitlement… models unlock on the next picker open or refresh.", pending.warning)
    }

    @Test
    fun aModelLimitResetsAtItsOwnTimeAndAPastOneIsDropped() {
        val shown = facts("""["a","b"]""", """"limit":{"scope":"models","models":{"a":"2026-11-15T09:30:00+00:00","b":"2026-11-15T07:00:00+00:00"}}""")

        assertEquals("2026-11-15T09:30:00+00:00", shown.models["a"]?.resetsAt)
        assertNull(shown.models["b"])
        assertFalse(shown.limited)
    }

    @Test
    fun anAccountLimitIsTheProvidersEvenWithoutATime() {
        val ahead = facts("""["a"]""", """"limit":{"scope":"account","resets_at":"2026-11-15T09:30:00+00:00"}""")
        assertTrue(ahead.limited)
        assertEquals("2026-11-15T09:30:00+00:00", ahead.limitedUntil)

        val unknown = facts("""["a"]""", """"limit":{"scope":"account"}""")
        assertTrue(unknown.limited)
        assertNull(unknown.limitedUntil)

        assertFalse(facts("""["a"]""", """"limit":{"scope":"account","resets_at":"2026-11-15T07:59:00+00:00"}""").limited)
    }

    @Test
    fun theWarningIsTheCoresWordsAndABlankOneIsNone() {
        assertEquals("Copilot is slow today", facts("""["a"]""", """"warning":" Copilot is slow today """").warning)
        assertNull(facts("""["a"]""", """"warning":"  """").warning)
    }

    @Test
    fun usageWindowsAreTightestFirstAndARolledOverOneIsDropped() {
        val shown = facts("""["a"]""", """"usage":{"windows":[
            {"label":"Week","used_percent":20.4,"resets_at":"2026-11-20T00:00:00+00:00"},
            {"label":"Session","used_percent":63.6,"resets_at":"2026-11-15T10:00:00+00:00"},
            {"label":"Day","used_percent":99,"resets_at":"2026-11-15T06:00:00+00:00"}]}""").usage

        assertEquals(listOf(UsageWindow("Session", 64, "2026-11-15T10:00:00+00:00"), UsageWindow("Week", 20, "2026-11-20T00:00:00+00:00")),
                     shown)
    }

    @Test
    fun aPoolShowsOneLinePerAccountNeverAnAverage() {
        val shown = facts("""["a"]""", """"usage":{"windows":[{"label":"Legacy","used_percent":50}],"accounts":[
            {"id":"1","label":"work","state":"ready","windows":[
                {"label":"Session","used_percent":30,"resets_at":"2026-11-15T10:00:00+00:00"},
                {"label":"Week","used_percent":70,"resets_at":"2026-11-20T00:00:00+00:00"}]},
            {"id":"2","label":"home","state":"limited","resets_at":"2026-11-15T12:00:00+00:00"},
            {"id":"3","label":"spare","state":"unknown"},
            {"id":"4","label":"","state":"ready","windows":[]}]}""").usage

        assertEquals(listOf(UsageWindow("work", 70, "2026-11-20T00:00:00+00:00"), UsageWindow("home", 100, "2026-11-15T12:00:00+00:00"),
                            UsageWindow("spare", null, null), UsageWindow("Account 4", null, null)), shown)
    }

    @Test
    fun aRowWithNoFactsIsNone() {
        assertEquals(ProviderFacts.NONE, ProviderFacts.of(bridgeJson.decodeFromString(ModelOptionProvider.serializer(),
                                                                                    """{"slug":"x","name":"X","models":["m"]}"""), NOW))
    }

    @Test
    fun aResetShowsThePhonesClockTodayElseTheDate() {
        assertEquals("11:00", resetTime("2026-11-15T10:00:00+00:00", NOW, ROME))
        assertEquals("11:00", resetTime("2026-11-15T10:00:00.123456+00:00", NOW, ROME))
        assertEquals("2026-11-17 11:00", resetTime("2026-11-17T10:00:00Z", NOW, ROME))
        assertEquals("soon", resetTime("soon", NOW, ROME))
        // Tomorrow, but less than 6 h ahead: the clock alone. With no offset the time is the phone's own.
        assertEquals("02:00", resetTime("2026-11-16T01:00:00+00:00", NOW + 14 * 3_600_000L, ROME))
        assertEquals("10:00", resetTime("2026-11-15T10:00:00", NOW, ROME))
    }
}
