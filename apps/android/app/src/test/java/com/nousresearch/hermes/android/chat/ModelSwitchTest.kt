package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.setup.CustomModels
import com.nousresearch.hermes.android.setup.ModelCatalog
import com.nousresearch.hermes.android.setup.ModelCatalogTest
import com.nousresearch.hermes.android.setup.ModelCatalogTest.Companion.GPT5
import com.nousresearch.hermes.android.setup.ModelCatalogTest.Companion.GPT5_PRO
import com.nousresearch.hermes.android.setup.ModelCatalogTest.Companion.NOW
import com.nousresearch.hermes.android.setup.ModelCatalogTest.Companion.V4_PRO
import com.nousresearch.hermes.android.setup.ModelRow
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.protocol.Method
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat's model picker (models-08, bet 5): a pick switches only this chat's session, in place; the default is Setup's. */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher: a launched pick runs until it suspends
class ModelSwitchTest {
    private val optionsJson = """{"model":"gpt-5","provider":"copilot","providers":[
        {"slug":"copilot","name":"GitHub Copilot","authenticated":true,"models":["gpt-5","gpt-5-pro"],
         "unavailable_models":["gpt-5-pro"],"pricing":{"gpt-5":{"input":"$1.25","output":"$10.00","free":false}}},
        {"slug":"nous","name":"Nous Portal","authenticated":false,"models":["nous/hermes-4"]},
        {"slug":"deepseek","name":"DeepSeek","authenticated":true,"models":["deepseek-v4-pro","deepseek-v4-flash"],
         "limit":{"scope":"models","models":{"deepseek-v4-flash":"2026-11-15T09:00:00Z"}}}]}"""

    private var sid: String? = "s1"
    private var onOptions: () -> Unit = {}
    private var configSet: (JsonObject) -> String = { p -> """{"key":"model","value":"${modelOf(p)}","scope":"session"}""" }
    private val core = FakeCoreGateway { method, params ->
        when (method) {
            "model.options" -> { onOptions(); optionsJson }
            "config.set" -> configSet(params)
            else -> error("unexpected $method")
        }
    }
    private val custom = CustomModels.InMemory()
    private val switch = ModelSwitch(core, session = { sid }, custom = custom, now = { NOW })

    private fun methods() = core.calls.map { it.first }
    private fun sets() = core.calls.filter { it.first == "config.set" }.map { it.second }
    private fun valueOf(params: JsonObject) = params.getValue("value").jsonPrimitive.content
    private fun modelOf(params: JsonObject) = valueOf(params).substringBefore(" ")
    private fun confirmed(params: JsonObject) = params["confirm_expensive_model"]?.jsonPrimitive?.boolean == true
    private fun ui() = checkNotNull(switch.state.value) { "the picker is closed" }

    private fun deferred(model: String) = """{"key":"model","value":"$model","deferred":true}"""
    private fun asksFirst(model: String) =
        """{"key":"model","value":"$model","confirm_required":true,"confirm_message":"$model costs more"}"""

    /** [core] with each call to [method] held until its gate in [gates] (in call order) opens; a call past them is not held. */
    private fun gated(gates: List<CompletableDeferred<Unit>>, method: String = "config.set"): CoreGateway = object : CoreGateway by core {
        private var held = 0
        override suspend fun <P, R> call(called: Method<P, R>, params: P): R {
            if (called.name == method && held < gates.size) gates[held++].await()
            return core.call(called, params)
        }
    }

    @Test
    fun openingReadsThisChatsCatalogFromTheCoresCache() = runTest {
        var seen: PickerUi? = PickerUi(query = "not read")
        onOptions = { seen = switch.state.value }

        switch.open()

        assertEquals("loading while the core lists", PickerUi(), seen)
        assertEquals(ModelCatalogTest.catalog(), ui().catalog)
        assertNull(ui().loadFailed)
        assertEquals(listOf("model.options"), methods())
        val asked = core.calls.single().second
        assertEquals("s1", asked.getValue("session_id").jsonPrimitive.content)
        assertFalse("cache-only: never a refresh", asked["refresh"]?.jsonPrimitive?.boolean == true)
        assertFalse("signed-in providers only", asked["include_unconfigured"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun aPickSwitchesOnlyThisChatInPlaceAndNeverTheDefault() = runTest {
        switch.open()

        switch.pick(GPT5)

        assertEquals("no session.create, no setup.*: the chat is not reopened", listOf("model.options", "config.set"), methods())
        val sent = sets().single()
        assertEquals("s1", sent.getValue("session_id").jsonPrimitive.content)
        assertEquals("model", sent.getValue("key").jsonPrimitive.content)
        assertEquals("gpt-5 --provider copilot", valueOf(sent))
        assertFalse("never the default", "--global" in valueOf(sent))
        assertFalse("the core decides persistence", "--session" in valueOf(sent))
        assertFalse(confirmed(sent))
        assertNull("switched: the sheet closes, the chat's model follows session.info", switch.state.value)
    }

    @Test
    fun aMixtureOfAgentsPickStaysInThisChat() = runTest {
        switch.open()

        switch.pick(ModelRow("moa", "Mixture of Agents", "default"))

        assertEquals("default --provider moa --session", valueOf(sets().single()))
    }

    @Test
    fun aPickDuringATurnAppliesFromTheNextMessage() = runTest {
        configSet = { p -> deferred(modelOf(p)) }
        switch.open()

        switch.pick(GPT5)

        assertEquals("gpt-5", ui().next)
        assertNull(ui().switching)
        assertEquals(ModelCatalogTest.catalog(), ui().catalog)
        switch.close()
        assertNull(switch.state.value)
    }

    @Test
    fun anExpensiveModelAsksInTheCoresWordsAndIsSentAgainOnlyOnceConfirmed() = runTest {
        configSet = { p -> if (confirmed(p)) """{"key":"model","value":"${modelOf(p)}"}""" else asksFirst(modelOf(p)) }
        switch.open()

        switch.pick(GPT5)
        assertEquals(Asked(GPT5, "gpt-5 costs more"), ui().confirm)
        assertNull(ui().switching)
        assertEquals(1, sets().size)

        switch.keep()
        assertNull(ui().confirm)
        assertEquals("Keep current model sends nothing", 1, sets().size)

        switch.pick(GPT5)
        switch.confirm()
        assertEquals(listOf(false, false, true), sets().map(::confirmed))
        assertEquals("gpt-5 --provider copilot", valueOf(sets().last()))
        assertNull("switched", switch.state.value)

        configSet = { p -> asksFirst(modelOf(p)) }
        switch.open()
        switch.pick(GPT5)
        switch.confirm()
        assertTrue(confirmed(sets().last()))
        assertEquals("a confirmed resend the core still holds is a failure in its words", "gpt-5 costs more", ui().failed)
        assertNull(ui().confirm)
    }

    @Test
    fun aRefusalSaysTheSwitchFailedInTheCoresWordsAndTheListStays() = runTest {
        val words = "Model switch to gpt-5 failed (boom); staying on deepseek-v4-pro."
        configSet = { throw CoreError(5001, words) }
        switch.open()

        switch.pick(GPT5)

        assertEquals(words, ui().failed)
        assertEquals(ModelCatalogTest.catalog(), ui().catalog)
        assertNull(ui().switching)

        configSet = { p -> deferred(modelOf(p)) }
        switch.pick(V4_PRO)
        assertNull("another pick clears the old failure", ui().failed)
        assertEquals("deepseek-v4-pro", ui().next)
    }

    @Test
    fun aLockedModelSaysWhyAndIsNeverSent() = runTest {
        switch.open()

        switch.pick(GPT5_PRO)

        assertEquals(listOf("model.options"), methods())
        assertEquals("copilot:gpt-5-pro", ui().locked)
        switch.query("x")
        assertNull(ui().locked)
        assertEquals("x", ui().query)
    }

    @Test
    fun theLastPickWinsAndAnOlderAnswerIsNotShown() = runTest(UnconfinedTestDispatcher()) {
        val first = CompletableDeferred<Unit>()
        configSet = { p -> if (modelOf(p) == "gpt-5") throw CoreError(5001, "boom") else deferred(modelOf(p)) }
        val other = ModelSwitch(gated(listOf(first)), session = { sid }, now = { NOW })
        other.open()

        val a = launch { other.pick(GPT5) }
        assertEquals(GPT5, other.state.value?.switching)
        other.pick(V4_PRO)
        assertEquals("deepseek-v4-pro", other.state.value?.next)
        first.complete(Unit)
        a.join()

        assertNull("A's failure lands nowhere", other.state.value?.failed)
        assertEquals("deepseek-v4-pro", other.state.value?.next)
        assertNull(other.state.value?.switching)

        val again = CompletableDeferred<Unit>()
        configSet = { p -> if (modelOf(p) == "gpt-5") asksFirst("gpt-5") else deferred(modelOf(p)) }
        val third = ModelSwitch(gated(listOf(again)), session = { sid }, now = { NOW })
        third.open()
        val b = launch { third.pick(GPT5) }
        third.pick(V4_PRO)
        again.complete(Unit)
        b.join()

        assertNull("A's confirm lands nowhere", third.state.value?.confirm)
        assertEquals("deepseek-v4-pro", third.state.value?.next)
    }

    @Test
    fun aTypedIdIsKeptOnlyOnceTheCoreTookIt() = runTest {
        switch.open()
        switch.addCustom()
        switch.query("openai/gpt-6")
        assertTrue(ui().adding)
        val typed = checkNotNull(ui().catalog).typed(ui().query)
        assertEquals(listOf("copilot", "deepseek"), typed.map { it.provider })

        switch.pick(typed.first())
        assertEquals(listOf("openai/gpt-6"), custom["copilot"])
        assertEquals(emptyList<String>(), custom["deepseek"])

        configSet = { throw CoreError(5001, "unknown model") }
        switch.open()
        switch.pick(ModelRow("deepseek", "DeepSeek", "nope/model", custom = true))
        assertEquals("refused: not kept", emptyList<String>(), custom["deepseek"])

        configSet = { p -> """{"key":"model","value":"${modelOf(p)}"}""" }
        switch.pick(V4_PRO)
        assertEquals("a listed model is never kept", emptyList<String>(), custom["deepseek"])
        switch.open()
        assertTrue("the kept id is listed at the next open",
                   checkNotNull(ui().catalog).rows.any { it.key == "copilot:openai/gpt-6" && it.custom })
    }

    @Test
    fun withNoChatNothingIsSent() = runTest {
        sid = null
        switch.open()
        assertNull(core.calls.single().second["session_id"])
        assertNotNull(ui().catalog)

        switch.pick(GPT5)

        assertEquals(listOf("model.options"), methods())
        assertEquals(ModelSwitch.NO_CHAT, ui().failed)
    }

    @Test
    fun aPickerClosedMeanwhileStaysClosed() = runTest(UnconfinedTestDispatcher()) {
        val listing = CompletableDeferred<Unit>()
        val listed = ModelSwitch(gated(listOf(listing), method = "model.options"), session = { sid }, now = { NOW })
        val open = launch { listed.open() }
        assertEquals(PickerUi(), listed.state.value)
        listed.close()
        listing.complete(Unit)
        open.join()
        assertNull("the list lands nowhere", listed.state.value)

        val setting = CompletableDeferred<Unit>()
        configSet = { p -> deferred(modelOf(p)) }
        val picked = ModelSwitch(gated(listOf(setting)), session = { sid }, now = { NOW })
        picked.open()
        val pick = launch { picked.pick(GPT5) }
        picked.close()
        setting.complete(Unit)
        pick.join()
        assertNull("the answer lands nowhere", picked.state.value)

        picked.pick(GPT5)
        assertEquals("a pick with no picker open sends nothing", 1, sets().size)
    }
}
