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

        assertEquals(listOf(UsageWindow.Known("Session", 64, "2026-11-15T10:00:00+00:00"), UsageWindow.Known("Week", 20,
                                                                                                         "2026-11-20T00:00:00+00:00")),
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

        assertEquals(listOf(UsageWindow.Known("work", 70, "2026-11-20T00:00:00+00:00"), UsageWindow.Limited("home",
                                                                                                         "2026-11-15T12:00:00+00:00"),
                            UsageWindow.Unknown("spare"), UsageWindow.Unknown("Account 4")), shown)
    }

    /** Each pool account keeps the core's state: limited with or without a reset, signed out, or not known (improve #3). */
    @Test
    fun aPoolAccountKeepsItsStateNeverAPercentageForALimitOrASignOut() {
        val shown = facts("""["a"]""", """"usage":{"accounts":[
            {"id":"1","label":"capped","state":"limited"},
            {"id":"2","label":"cooling","state":"limited","resets_at":"2026-11-15T12:00:00+00:00",
             "windows":[{"label":"Session","used_percent":100,"resets_at":"2026-11-15T12:00:00+00:00"}]},
            {"id":"3","label":"over","state":"limited","resets_at":"2026-11-15T07:00:00+00:00"},
            {"id":"4","label":"revoked","state":"unavailable"},
            {"id":"5","label":"blind","state":"unknown","windows":[{"label":"Session","used_percent":40}]},
            {"id":"6","label":"odd","state":"paused","windows":[{"label":"Session","used_percent":40}]}]}""").usage

        assertEquals(listOf(UsageWindow.Limited("capped", null), UsageWindow.Limited("cooling", "2026-11-15T12:00:00+00:00"),
                            // A reset already past is not fresh quota: not known, never "Limited until" a time gone by.
                            UsageWindow.Unknown("over"),
                            UsageWindow.Unavailable("revoked"),
                            // Windows on an account the core does not call ready are no gauge.
                            UsageWindow.Unknown("blind"), UsageWindow.Unknown("odd")), shown)
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

    /** An open picker reads its facts against its own, later clock (improve #8): a reset now past ends what it timed. */
    @Test
    fun aResetThePickersClockHasPassedIsNoLongerUpcoming() {
        val later = NOW + 2 * 3_600_000L  // 10:00Z
        val account = facts("""["a"]""", """"limit":{"scope":"account","resets_at":"2026-11-15T09:00:00+00:00"},
            "usage":{"windows":[{"label":"Session","used_percent":63,"resets_at":"2026-11-15T09:00:00+00:00"},
                                {"label":"Week","used_percent":20,"resets_at":"2026-11-20T00:00:00+00:00"}]}""")
        assertEquals("as of the clock it was built at, nothing changes", account, account.asOf(NOW))
        val shown = account.asOf(later)
        assertFalse(shown.limited)
        assertNull(shown.limitedUntil)
        assertEquals(listOf(UsageWindow.Unknown("Session"), UsageWindow.Known("Week", 20, "2026-11-20T00:00:00+00:00")), shown.usage)

        val models = facts("""["a","b","c"]""", """"limit":{"scope":"models","models":{"a":"2026-11-15T09:00:00+00:00",
            "b":"2026-11-16T10:00:00+00:00","c":"2026-11-15T09:00:00+00:00"}},
            "pricing":{"c":{"input":"$1.00","output":"$2.00","free":false}}""").asOf(later).models
        assertNull(models["a"])
        assertEquals("2026-11-16T10:00:00+00:00", models["b"]?.resetsAt)
        assertEquals(ModelFacts(input = "$1.00", output = "$2.00"), models["c"])

        val pool = facts("""["a"]""", """"usage":{"accounts":[
            {"id":"1","label":"home","state":"limited","resets_at":"2026-11-15T09:00:00+00:00"},
            {"id":"2","label":"old","state":"unavailable"}]}""").asOf(later).usage
        assertEquals(listOf(UsageWindow.Unknown("home"), UsageWindow.Unavailable("old")), pool)

        // A limit with no time, or one the app cannot read, stands: the core has not said it is over.
        assertTrue(facts("""["a"]""", """"limit":{"scope":"account"}""").asOf(later).limited)
        assertTrue(facts("""["a"]""", """"limit":{"scope":"account","resets_at":"after lunch"}""").asOf(later).limited)
    }

    /** One model's facts as of a later clock, by the same rule (the chat's picker reads one row at a time, models-08). */
    @Test
    fun aModelsPassedResetIsDroppedAsOfALaterClock() {
        val flash = ModelFacts(resetsAt = "2026-11-15T09:00:00Z")
        assertEquals("ahead of the clock: kept", flash, flash.asOf(NOW))
        assertEquals("past: dropped", ModelFacts(), flash.asOf(NOW + 2 * 3_600_000L))
        val priced = ModelFacts(input = "$1.00", output = "$2.00", locked = true, resetsAt = "2026-11-15T09:00:00Z")
        assertEquals("only the reset goes", ModelFacts(input = "$1.00", output = "$2.00", locked = true), priced.asOf(NOW + 2 * 3_600_000L))
        val unreadable = ModelFacts(resetsAt = "after lunch")
        assertEquals("an unreadable time stands", unreadable, unreadable.asOf(NOW + 2 * 3_600_000L))
    }

    /** The same reset reads against the clock it is given: tomorrow's loses its date once the clock reaches that day (improve #8). */
    @Test
    fun aResetReadsAgainstTheClockItIsGiven() {
        val at = "2026-11-16T10:00:00+00:00"  // 11:00 in Rome, tomorrow
        assertEquals("2026-11-16 11:00", resetTime(at, NOW, ROME))
        assertEquals("11:00", resetTime(at, NOW + 16 * 3_600_000L, ROME))  // 01:00 in Rome on the 16th: the same day
    }
}
